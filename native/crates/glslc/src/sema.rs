use crate::{
    CompileError, CompileErrors, ErrorKind, Location, Stage, builtins,
    parse::{Ast, Declaration, Expression, Qualifier, Statement, TypeSpecifier},
};

#[derive(Debug, Eq, PartialEq)]
pub enum Type {
    Void,
    Bool,
    Int,
    UInt,
    Int64,
    UInt64,
    Float,
    Double,
    Vector {
        scalar: Scalar,
        size: u8,
    },
    Matrix {
        scalar: Scalar,
        columns: u8,
        rows: u8,
    },
    Array {
        element: Box<Type>,
        size: Option<u64>,
    },
    Struct {
        name: String,
        fields: Vec<Field>,
    },
    Sampler(String),
    Image(String),
    AtomicCounter,
    Unknown(String),
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Scalar {
    Bool,
    Int,
    UInt,
    Int64,
    UInt64,
    Float,
    Double,
}
#[derive(Debug, Eq, PartialEq)]
pub struct Field {
    pub name: String,
    pub ty: Type,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ValueCategory {
    RValue,
    LValue,
    Constant,
}
#[derive(Debug, Eq, PartialEq)]
pub struct TypedExpression {
    pub ty: Type,
    pub category: ValueCategory,
    pub constant: Option<ConstantValue>,
}
#[derive(Debug, Eq, PartialEq)]
pub enum ConstantValue {
    Bool(bool),
    Int(i64),
    UInt(u64),
    FloatBits(u64),
}
#[derive(Debug, Eq, PartialEq)]
pub struct TypedDeclaration {
    pub name: String,
    pub ty: Type,
    pub constant: Option<ConstantValue>,
    pub is_function: bool,
}
#[derive(Debug, Eq, PartialEq)]
pub struct FunctionSignature {
    pub name: String,
    pub return_type: Type,
    pub parameters: Vec<Type>,
}
#[derive(Debug, Eq, PartialEq)]
pub struct TypedModule {
    pub declarations: Vec<TypedDeclaration>,
    pub functions: Vec<FunctionSignature>,
}

fn type_copy(value: &Type) -> Type {
    match value {
        Type::Void => Type::Void,
        Type::Bool => Type::Bool,
        Type::Int => Type::Int,
        Type::UInt => Type::UInt,
        Type::Int64 => Type::Int64,
        Type::UInt64 => Type::UInt64,
        Type::Float => Type::Float,
        Type::Double => Type::Double,
        Type::Vector { scalar, size } => Type::Vector {
            scalar: *scalar,
            size: *size,
        },
        Type::Matrix {
            scalar,
            columns,
            rows,
        } => Type::Matrix {
            scalar: *scalar,
            columns: *columns,
            rows: *rows,
        },
        Type::Array { element, size } => Type::Array {
            element: Box::new(type_copy(element)),
            size: *size,
        },
        Type::Struct { name, fields } => Type::Struct {
            name: String::from(name),
            fields: fields
                .iter()
                .map(|field| Field {
                    name: String::from(field.name.as_str()),
                    ty: type_copy(&field.ty),
                })
                .collect(),
        },
        Type::Sampler(name) => Type::Sampler(String::from(name)),
        Type::Image(name) => Type::Image(String::from(name)),
        Type::AtomicCounter => Type::AtomicCounter,
        Type::Unknown(name) => Type::Unknown(String::from(name)),
    }
}
fn constant_copy(value: &Option<ConstantValue>) -> Option<ConstantValue> {
    match value {
        Some(ConstantValue::Bool(value)) => Some(ConstantValue::Bool(*value)),
        Some(ConstantValue::Int(value)) => Some(ConstantValue::Int(*value)),
        Some(ConstantValue::UInt(value)) => Some(ConstantValue::UInt(*value)),
        Some(ConstantValue::FloatBits(value)) => Some(ConstantValue::FloatBits(*value)),
        None => None,
    }
}
fn scalar_type(value: Scalar) -> Type {
    match value {
        Scalar::Bool => Type::Bool,
        Scalar::Int => Type::Int,
        Scalar::UInt => Type::UInt,
        Scalar::Int64 => Type::Int64,
        Scalar::UInt64 => Type::UInt64,
        Scalar::Float => Type::Float,
        Scalar::Double => Type::Double,
    }
}
fn numeric(value: &Type) -> bool {
    matches!(
        value,
        Type::Int | Type::UInt | Type::Int64 | Type::UInt64 | Type::Float | Type::Double
    )
}
fn is_scalar_numeric(value: &Type) -> bool {
    numeric(value)
}
fn constructor_type(name: &str) -> Option<Type> {
    let scalar = match name {
        "bool" => Some(Scalar::Bool),
        "int" => Some(Scalar::Int),
        "uint" => Some(Scalar::UInt),
        "float" => Some(Scalar::Float),
        "double" => Some(Scalar::Double),
        "int64_t" => Some(Scalar::Int64),
        "uint64_t" => Some(Scalar::UInt64),
        _ => None,
    };
    if let Some(scalar) = scalar {
        return Some(scalar_type(scalar));
    }
    let (prefix, scalar) = if let Some(size) = name.strip_prefix("vec") {
        (size, Scalar::Float)
    } else if let Some(size) = name.strip_prefix("dvec") {
        (size, Scalar::Double)
    } else if let Some(size) = name.strip_prefix("ivec") {
        (size, Scalar::Int)
    } else if let Some(size) = name.strip_prefix("uvec") {
        (size, Scalar::UInt)
    } else if let Some(size) = name.strip_prefix("i64vec") {
        (size, Scalar::Int64)
    } else if let Some(size) = name.strip_prefix("u64vec") {
        (size, Scalar::UInt64)
    } else {
        (name.strip_prefix("bvec")?, Scalar::Bool)
    };
    prefix
        .parse::<u8>()
        .ok()
        .filter(|size| (2..=4).contains(size))
        .map(|size| Type::Vector { scalar, size })
}
fn conversion_cost(from: &Type, to: &Type) -> u8 {
    if from == to {
        0
    } else if matches!((from, to), (Type::Vector { .. }, Type::Vector { .. })) {
        1
    } else {
        2
    }
}

struct Analyzer {
    stage: Stage,
    scopes: Vec<Vec<TypedDeclaration>>,
    functions: Vec<FunctionSignature>,
    errors: Vec<CompileError>,
    error_location: Option<(String, u32, u32)>,
}
impl Analyzer {
    fn new(stage: Stage) -> Self {
        let mut analyzer = Self {
            stage,
            scopes: vec![Vec::new()],
            functions: builtins::functions(stage),
            errors: Vec::new(),
            error_location: None,
        };
        for (name, ty) in builtins::variables(stage) {
            analyzer.declare(TypedDeclaration {
                name: String::from(name),
                ty,
                constant: None,
                is_function: false,
            });
        }
        analyzer
    }
    fn error(&mut self, message: impl Into<String>) {
        self.errors
            .push(CompileError::new(ErrorKind::Semantic, message, None));
    }
    fn push(&mut self) {
        self.scopes.push(Vec::new());
    }
    fn pop(&mut self) {
        let _ = self.scopes.pop();
    }
    fn declare(&mut self, item: TypedDeclaration) {
        let duplicate = self
            .scopes
            .last()
            .is_some_and(|scope| scope.iter().any(|old| old.name == item.name));
        if duplicate {
            self.error(format!("redeclaration of `{}`", item.name));
        }
        if let Some(scope) = self.scopes.last_mut() {
            scope.push(item);
        }
    }
    fn lookup(&self, name: &str) -> Option<&TypedDeclaration> {
        self.scopes
            .iter()
            .rev()
            .find_map(|scope| scope.iter().rev().find(|item| item.name == name))
    }
    fn resolve(&mut self, specifier: &TypeSpecifier) -> Type {
        match specifier {
            TypeSpecifier::Void => Type::Void,
            TypeSpecifier::Bool => Type::Bool,
            TypeSpecifier::Int => Type::Int,
            TypeSpecifier::UInt => Type::UInt,
            TypeSpecifier::Int64 => Type::Int64,
            TypeSpecifier::UInt64 => Type::UInt64,
            TypeSpecifier::Float => Type::Float,
            TypeSpecifier::Double => Type::Double,
            TypeSpecifier::Vec(size) => Type::Vector {
                scalar: Scalar::Float,
                size: *size,
            },
            TypeSpecifier::DVec(size) => Type::Vector {
                scalar: Scalar::Double,
                size: *size,
            },
            TypeSpecifier::I64Vec(size) => Type::Vector {
                scalar: Scalar::Int64,
                size: *size,
            },
            TypeSpecifier::U64Vec(size) => Type::Vector {
                scalar: Scalar::UInt64,
                size: *size,
            },
            TypeSpecifier::IVec(size) => Type::Vector {
                scalar: Scalar::Int,
                size: *size,
            },
            TypeSpecifier::UVec(size) => Type::Vector {
                scalar: Scalar::UInt,
                size: *size,
            },
            TypeSpecifier::BVec(size) => Type::Vector {
                scalar: Scalar::Bool,
                size: *size,
            },
            TypeSpecifier::Mat(columns, rows) => Type::Matrix {
                scalar: Scalar::Float,
                columns: *columns,
                rows: *rows,
            },
            TypeSpecifier::DMat(columns, rows) => Type::Matrix {
                scalar: Scalar::Double,
                columns: *columns,
                rows: *rows,
            },
            TypeSpecifier::Sampler(name) if name == "atomic_uint" => Type::AtomicCounter,
            TypeSpecifier::Sampler(name) => Type::Sampler(String::from(name)),
            TypeSpecifier::Image(name) => Type::Image(String::from(name)),
            TypeSpecifier::Struct(name, members) | TypeSpecifier::Interface(name, members) => {
                let fields = members
                    .iter()
                    .filter_map(|member| {
                        member.name.as_ref().map(|field_name| Field {
                            name: String::from(field_name),
                            ty: self.declared_type(member),
                        })
                    })
                    .collect();
                Type::Struct {
                    name: String::from(name),
                    fields,
                }
            }
            TypeSpecifier::Named(name) => self
                .lookup(name)
                .map(|item| type_copy(&item.ty))
                .unwrap_or_else(|| Type::Unknown(String::from(name))),
        }
    }
    fn declared_type(&mut self, declaration: &Declaration) -> Type {
        let mut ty = self.resolve(&declaration.type_specifier);
        for expression in declaration.array.iter().rev() {
            let size = self.constant_integer(expression);
            ty = Type::Array {
                element: Box::new(ty),
                size,
            };
        }
        ty
    }
    fn parameter_type(&mut self, parameter: &crate::parse::Parameter) -> Type {
        let mut ty = self.resolve(&parameter.type_specifier);
        for expression in parameter.array.iter().rev() {
            let size = self.constant_integer(expression);
            ty = Type::Array {
                element: Box::new(ty),
                size,
            };
        }
        ty
    }
    fn constant_integer(&mut self, expression: &Expression) -> Option<u64> {
        match self.expression(expression).constant {
            Some(ConstantValue::Int(value)) if value >= 0 => u64::try_from(value).ok(),
            Some(ConstantValue::UInt(value)) => Some(value),
            _ => {
                self.error("array size must be a constant integer");
                None
            }
        }
    }
    fn compatible(&self, from: &Type, to: &Type) -> bool {
        if from == to {
            return true;
        }
        match (from, to) {
            (a, b) if numeric(a) && numeric(b) => true,
            (Type::Vector { scalar: a, size: x }, Type::Vector { scalar: b, size: y }) => {
                x == y && (*a == *b || (*a != Scalar::Bool && *b != Scalar::Bool))
            }
            _ => false,
        }
    }
    fn operator_type(&self, left: &Type, right: &Type) -> Option<Type> {
        if self.compatible(left, right) {
            return Some(type_copy(left));
        }
        if self.compatible(right, left) {
            return Some(type_copy(right));
        }
        match (left, right) {
            (Type::Vector { scalar, size }, scalar_type)
                if is_scalar_numeric(scalar_type) && *scalar != Scalar::Bool =>
            {
                Some(Type::Vector {
                    scalar: *scalar,
                    size: *size,
                })
            }
            (scalar_type, Type::Vector { scalar, size })
                if is_scalar_numeric(scalar_type) && *scalar != Scalar::Bool =>
            {
                Some(Type::Vector {
                    scalar: *scalar,
                    size: *size,
                })
            }
            (
                Type::Matrix {
                    scalar,
                    columns,
                    rows,
                },
                scalar_type,
            ) if is_scalar_numeric(scalar_type) => Some(Type::Matrix {
                scalar: *scalar,
                columns: *columns,
                rows: *rows,
            }),
            (
                scalar_type,
                Type::Matrix {
                    scalar,
                    columns,
                    rows,
                },
            ) if is_scalar_numeric(scalar_type) => Some(Type::Matrix {
                scalar: *scalar,
                columns: *columns,
                rows: *rows,
            }),
            _ => None,
        }
    }
    fn constructor_argument(&self, value: &Type, target: &Type) -> bool {
        if self.compatible(value, target) {
            return true;
        }
        match (value, target) {
            (source, Type::Vector { scalar, .. }) if is_scalar_numeric(source) => {
                *scalar != Scalar::Bool
            }
            (Type::Vector { scalar, .. }, Type::Vector { scalar: target, .. }) => {
                *scalar != Scalar::Bool && *target != Scalar::Bool
            }
            (source, Type::Matrix { scalar, .. }) if is_scalar_numeric(source) => {
                *scalar != Scalar::Bool
            }
            _ => false,
        }
    }
    fn constructor_components(value: &Type) -> u8 {
        match value {
            Type::Vector { size, .. } => *size,
            Type::Matrix { columns, rows, .. } => columns.saturating_mul(*rows),
            _ => 1,
        }
    }
    fn expression(&mut self, expression: &Expression) -> TypedExpression {
        match expression {
            Expression::Literal(text) => self.literal(text),
            Expression::Name(name) => {
                let found = self
                    .lookup(name)
                    .map(|item| (type_copy(&item.ty), item.constant.as_ref().map(value_copy)));
                match found {
                    Some((ty, constant)) => TypedExpression {
                        ty,
                        category: if constant.is_some() {
                            ValueCategory::Constant
                        } else {
                            ValueCategory::LValue
                        },
                        constant,
                    },
                    None => {
                        self.error(format!("use of undeclared identifier `{name}`"));
                        self.unknown()
                    }
                }
            }
            Expression::Unary(operator, value) => {
                let operand = self.expression(value);
                if matches!(operator.as_str(), "++" | "--")
                    && operand.category == ValueCategory::RValue
                {
                    self.error("increment operand is not an l-value");
                }
                if operator == "!" && operand.ty != Type::Bool {
                    self.error("logical negation requires bool");
                }
                let constant = match (operator.as_str(), operand.constant.as_ref()) {
                    ("+", Some(value)) => Some(value_copy(value)),
                    ("-", Some(ConstantValue::Int(value))) => {
                        Some(ConstantValue::Int(value.saturating_neg()))
                    }
                    ("-", Some(ConstantValue::UInt(value))) => i64::try_from(*value)
                        .ok()
                        .map(|value| ConstantValue::Int(value.saturating_neg())),
                    _ => None,
                };
                TypedExpression {
                    ty: operand.ty,
                    category: ValueCategory::RValue,
                    constant,
                }
            }
            Expression::Binary(operator, left, right) => self.binary(operator, left, right),
            Expression::Conditional(condition, yes, no) => {
                if self.expression(condition).ty != Type::Bool {
                    self.error("conditional expression requires bool");
                }
                let left = self.expression(yes);
                let right = self.expression(no);
                if !self.compatible(&left.ty, &right.ty) && !self.compatible(&right.ty, &left.ty) {
                    self.error("conditional operands have incompatible types");
                }
                TypedExpression {
                    ty: left.ty,
                    category: ValueCategory::RValue,
                    constant: None,
                }
            }
            Expression::Call(callee, arguments) => self.call(callee, arguments),
            Expression::Index(base, index) => {
                let value = self.expression(base);
                let position = self.expression(index);
                if position.ty != Type::Int && position.ty != Type::UInt {
                    self.error("index requires int or uint");
                }
                match value.ty {
                    Type::Array { element, .. } => TypedExpression {
                        ty: *element,
                        category: ValueCategory::LValue,
                        constant: None,
                    },
                    Type::Vector { scalar, .. } => TypedExpression {
                        ty: scalar_type(scalar),
                        category: ValueCategory::LValue,
                        constant: None,
                    },
                    Type::Matrix {
                        scalar, columns, ..
                    } => TypedExpression {
                        ty: Type::Vector {
                            scalar,
                            size: columns,
                        },
                        category: ValueCategory::LValue,
                        constant: None,
                    },
                    _ => {
                        self.error("indexing requires array, vector, or matrix");
                        self.unknown()
                    }
                }
            }
            Expression::Field(base, field) => {
                let value = self.expression(base);
                match value.ty {
                    Type::Struct { fields, .. } => fields
                        .into_iter()
                        .find(|item| item.name == *field)
                        .map(|item| TypedExpression {
                            ty: item.ty,
                            category: ValueCategory::LValue,
                            constant: None,
                        })
                        .unwrap_or_else(|| {
                            self.error(format!("unknown field `{field}`"));
                            self.unknown()
                        }),
                    Type::Vector { scalar, size } => {
                        let width = field.len();
                        let valid = width > 0
                            && width <= 4
                            && field
                                .chars()
                                .all(|value| "xyzwrgba stpq".contains(value) && value != ' ');
                        let ty = if valid && width > 1 {
                            let vector_size = match u8::try_from(width) {
                                Ok(value) => value,
                                Err(_) => size,
                            };
                            Type::Vector {
                                scalar,
                                size: vector_size,
                            }
                        } else {
                            scalar_type(scalar)
                        };
                        TypedExpression {
                            ty,
                            category: ValueCategory::LValue,
                            constant: None,
                        }
                    }
                    _ => {
                        self.error("field selection requires struct or vector");
                        self.unknown()
                    }
                }
            }
            Expression::Constructor(specifier, arguments) => {
                let ty = self.resolve(specifier);
                let mut components = 0u8;
                for argument in arguments {
                    let value = self.expression(argument);
                    if !self.constructor_argument(&value.ty, &ty) {
                        self.error("constructor argument has incompatible type");
                    }
                    components = components.saturating_add(Self::constructor_components(&value.ty));
                }
                if let Type::Vector { size, .. } = &ty
                    && components != *size
                    && components != 1
                {
                    self.error("constructor has the wrong number of components");
                }
                TypedExpression {
                    ty,
                    category: ValueCategory::RValue,
                    constant: None,
                }
            }
        }
    }
    fn unknown(&self) -> TypedExpression {
        TypedExpression {
            ty: Type::Unknown(String::from("<error>")),
            category: ValueCategory::RValue,
            constant: None,
        }
    }
    fn literal(&self, text: &str) -> TypedExpression {
        if text == "true" || text == "false" {
            return TypedExpression {
                ty: Type::Bool,
                category: ValueCategory::Constant,
                constant: Some(ConstantValue::Bool(text == "true")),
            };
        }
        if text.contains('.') || text.contains('e') || text.contains('E') || text.ends_with('f') {
            return TypedExpression {
                ty: Type::Float,
                category: ValueCategory::Constant,
                constant: text
                    .trim_end_matches('f')
                    .parse::<f64>()
                    .ok()
                    .map(|value| ConstantValue::FloatBits(value.to_bits())),
            };
        }
        if text.ends_with('u') || text.ends_with('U') {
            TypedExpression {
                ty: Type::UInt,
                category: ValueCategory::Constant,
                constant: text[..text.len().saturating_sub(1)]
                    .parse::<u64>()
                    .ok()
                    .map(ConstantValue::UInt),
            }
        } else {
            TypedExpression {
                ty: Type::Int,
                category: ValueCategory::Constant,
                constant: text.parse::<i64>().ok().map(ConstantValue::Int),
            }
        }
    }
    fn binary(&mut self, operator: &str, left: &Expression, right: &Expression) -> TypedExpression {
        let lhs = self.expression(left);
        let rhs = self.expression(right);
        let folded = match (operator, lhs.constant.as_ref(), rhs.constant.as_ref()) {
            ("+", Some(ConstantValue::Int(a)), Some(ConstantValue::Int(b))) => {
                Some(ConstantValue::Int(a.saturating_add(*b)))
            }
            ("-", Some(ConstantValue::Int(a)), Some(ConstantValue::Int(b))) => {
                Some(ConstantValue::Int(a.saturating_sub(*b)))
            }
            ("*", Some(ConstantValue::Int(a)), Some(ConstantValue::Int(b))) => {
                Some(ConstantValue::Int(a.saturating_mul(*b)))
            }
            ("/", Some(ConstantValue::Int(a)), Some(ConstantValue::Int(b))) => {
                (*b != 0).then(|| ConstantValue::Int(a / b))
            }
            ("+", Some(ConstantValue::UInt(a)), Some(ConstantValue::UInt(b))) => {
                Some(ConstantValue::UInt(a.saturating_add(*b)))
            }
            ("-", Some(ConstantValue::UInt(a)), Some(ConstantValue::UInt(b))) => {
                Some(ConstantValue::UInt(a.saturating_sub(*b)))
            }
            ("*", Some(ConstantValue::UInt(a)), Some(ConstantValue::UInt(b))) => {
                Some(ConstantValue::UInt(a.saturating_mul(*b)))
            }
            _ => None,
        };
        if operator == "=" || (operator.ends_with('=') && operator != "==") {
            if lhs.category != ValueCategory::LValue {
                self.error("assignment requires an l-value");
            }
            let assignable = self.compatible(&rhs.ty, &lhs.ty)
                || (operator != "=" && self.operator_type(&lhs.ty, &rhs.ty).is_some());
            if !assignable {
                self.error("assigned value has incompatible type");
            }
            return TypedExpression {
                ty: lhs.ty,
                category: ValueCategory::LValue,
                constant: None,
            };
        }
        if matches!(operator, "==" | "!=" | "<" | ">" | "<=" | ">=") {
            if !self.compatible(&lhs.ty, &rhs.ty) {
                self.error("comparison operands have incompatible types");
            }
            return TypedExpression {
                ty: Type::Bool,
                category: ValueCategory::RValue,
                constant: None,
            };
        }
        if matches!(operator, "&&" | "||" | "^^") && (lhs.ty != Type::Bool || rhs.ty != Type::Bool)
        {
            self.error("logical operators require bool operands");
        }
        let result_type = if matches!(operator, "&&" | "||" | "^^") {
            Some(Type::Bool)
        } else {
            self.operator_type(&lhs.ty, &rhs.ty)
        };
        let ty = match result_type {
            Some(value) => value,
            None => {
                self.error("operator operands have incompatible types");
                lhs.ty
            }
        };
        TypedExpression {
            ty,
            category: ValueCategory::RValue,
            constant: folded,
        }
    }
    fn call(&mut self, callee: &Expression, arguments: &[Expression]) -> TypedExpression {
        let name = match callee {
            Expression::Name(value) => value,
            _ => {
                self.error("call target is not a function");
                return self.unknown();
            }
        };
        let argument_types: Vec<Type> = arguments
            .iter()
            .map(|argument| self.expression(argument).ty)
            .collect();
        if matches!(name.as_str(), "atomicAdd" | "atomicExchange")
            && argument_types.first().is_some_and(|ty| {
                matches!(
                    ty,
                    Type::Double
                        | Type::Int64
                        | Type::UInt64
                        | Type::Vector {
                            scalar: Scalar::Double | Scalar::Int64 | Scalar::UInt64,
                            ..
                        }
                )
            })
        {
            self.errors.push(CompileError::new(
                ErrorKind::Unsupported,
                "Metal 3.2 has no device-scope atomic operation for double or 64-bit integer values",
                self.error_location.as_ref().map(|(file, line, column)| {
                    Location::new(file.as_str(), *line, *column)
                }),
            ));
            return self.unknown();
        }
        if let Some(target) = constructor_type(name) {
            let mut components = 0u8;
            for argument_type in &argument_types {
                if !self.constructor_argument(argument_type, &target) {
                    self.error("constructor argument has incompatible type");
                }
                components = components.saturating_add(Self::constructor_components(argument_type));
            }
            if let Type::Vector { size, .. } = &target
                && components != *size
                && components != 1
            {
                self.error("constructor has the wrong number of components");
            }
            return TypedExpression {
                ty: target,
                category: ValueCategory::RValue,
                constant: None,
            };
        }
        let candidate = self
            .functions
            .iter()
            .filter(|function| {
                function.name == *name
                    && function.parameters.len() == argument_types.len()
                    && function.parameters.iter().zip(argument_types.iter()).all(
                        |(expected, actual)| {
                            self.compatible(actual, expected)
                                || self.operator_type(actual, expected).is_some()
                        },
                    )
            })
            .min_by_key(|function| {
                function
                    .parameters
                    .iter()
                    .zip(argument_types.iter())
                    .map(|(expected, actual)| conversion_cost(actual, expected))
                    .sum::<u8>()
            });
        if let Some(function) = candidate {
            let return_type = type_copy(&function.return_type);
            TypedExpression {
                ty: return_type,
                category: ValueCategory::RValue,
                constant: None,
            }
        } else {
            self.error(format!("no matching function `{name}`"));
            self.unknown()
        }
    }
    fn statement(&mut self, statement: &Statement, return_type: &Type) {
        match statement {
            Statement::Compound(_, values) => {
                self.push();
                for value in values {
                    self.statement(value, return_type);
                }
                self.pop();
            }
            Statement::Declaration(value) => {
                let _ = self.declaration(value);
            }
            Statement::Expression(_, value) => {
                if let Some(value) = value {
                    let _ = self.expression(value);
                }
            }
            Statement::If(_, condition, yes, no) => {
                if self.expression(condition).ty != Type::Bool {
                    self.error("if condition requires bool");
                }
                self.statement(yes, return_type);
                if let Some(value) = no {
                    self.statement(value, return_type);
                }
            }
            Statement::Switch(_, value, body) => {
                let _ = self.expression(value);
                for item in body {
                    self.statement(item, return_type);
                }
            }
            Statement::Label(_, value, body) => {
                if let Some(value) = value {
                    let _ = self.expression(value);
                }
                self.statement(body, return_type);
            }
            Statement::Loop(_, init, condition, increment, body) => {
                if let Some(value) = init {
                    self.statement(value, return_type);
                }
                if let Some(value) = condition
                    && self.expression(value).ty != Type::Bool
                {
                    self.error("loop condition requires bool");
                }
                if let Some(value) = increment {
                    let _ = self.expression(value);
                }
                self.statement(body, return_type);
            }
            Statement::Jump(_, keyword, value) if keyword == "return" => match (return_type, value)
            {
                (Type::Void, None) => {}
                (Type::Void, Some(_)) => self.error("void function cannot return a value"),
                (_, Some(value)) => {
                    let actual = self.expression(value);
                    if !self.compatible(&actual.ty, return_type) {
                        self.error("return value has incompatible type");
                    }
                }
                (_, None) => self.error("non-void function must return a value"),
            },
            Statement::Jump(_, _, _) | Statement::Empty(_) | Statement::Error(_) => {}
        }
    }
    fn validate_qualifiers(&mut self, declaration: &Declaration) {
        let is_const = has_const(&declaration.qualifiers);
        if is_const && declaration.initializer.is_none() {
            self.error("const declaration requires an initializer");
        }
        let storage_count = declaration
            .qualifiers
            .iter()
            .filter(|qualifier| matches!(qualifier, Qualifier::Storage(_)))
            .count();
        if storage_count > 1 {
            self.error("conflicting storage qualifiers");
        }
    }
    fn declaration(&mut self, declaration: &Declaration) -> Option<TypedDeclaration> {
        self.validate_qualifiers(declaration);
        let ty = self.declared_type(declaration);
        let name = declaration.name.as_ref()?;
        let constant = if has_const(&declaration.qualifiers) {
            declaration.initializer.as_ref().and_then(|value| {
                let result = self.expression(value);
                if !self.compatible(&result.ty, &ty) {
                    self.error("initializer has incompatible type");
                }
                constant_copy(&result.constant)
            })
        } else {
            None
        };
        let item = TypedDeclaration {
            name: String::from(name),
            ty: type_copy(&ty),
            constant,
            is_function: false,
        };
        self.declare(TypedDeclaration {
            name: String::from(name),
            ty,
            constant: constant_copy(&item.constant),
            is_function: false,
        });
        Some(item)
    }
    fn run(mut self, ast: &Ast) -> Result<TypedModule, CompileErrors> {
        for declaration in &ast.declarations {
            if declaration.body.is_none()
                && let Some(name) = &declaration.name
            {
                let ty = self.declared_type(declaration);
                let constant = if has_const(&declaration.qualifiers) {
                    declaration
                        .initializer
                        .as_ref()
                        .and_then(|value| self.expression(value).constant)
                } else {
                    None
                };
                self.declare(TypedDeclaration {
                    name: String::from(name),
                    ty,
                    constant,
                    is_function: false,
                });
            }
        }
        for declaration in &ast.declarations {
            if let Some(name) = &declaration.name
                && declaration.body.is_some()
            {
                let return_type = self.resolve(&declaration.type_specifier);
                let parameters = declaration
                    .parameters
                    .iter()
                    .map(|parameter| self.parameter_type(parameter))
                    .collect();
                self.functions.push(FunctionSignature {
                    name: String::from(name),
                    return_type,
                    parameters,
                });
            }
        }
        let mut declarations = Vec::new();
        for declaration in &ast.declarations {
            if let Some(name) = &declaration.name
                && declaration.body.is_some()
            {
                let return_type = self.resolve(&declaration.type_specifier);
                self.push();
                for parameter in &declaration.parameters {
                    if let Some(parameter_name) = &parameter.name {
                        let ty = self.parameter_type(parameter);
                        self.declare(TypedDeclaration {
                            name: String::from(parameter_name),
                            ty,
                            constant: None,
                            is_function: false,
                        });
                    }
                }
                if let Some(body) = &declaration.body {
                    self.error_location = Some((
                        String::from(declaration.span.source_path.as_str()),
                        declaration.span.line,
                        declaration.span.column,
                    ));
                    self.statement(body, &return_type);
                    self.error_location = None;
                }
                self.pop();
                declarations.push(TypedDeclaration {
                    name: String::from(name),
                    ty: return_type,
                    constant: None,
                    is_function: true,
                });
            } else if declaration.initializer.is_some()
                && let Some(value) = &declaration.initializer
            {
                let _ = self.expression(value);
            }
        }
        if self.stage == Stage::Compute
            && !self
                .functions
                .iter()
                .any(|function| function.name == "main")
        {
            self.error("compute stage requires main");
        }
        if self.errors.is_empty() {
            Ok(TypedModule {
                declarations,
                functions: self.functions,
            })
        } else {
            Err(CompileErrors::from_vec(self.errors))
        }
    }
}
fn has_const(qualifiers: &[Qualifier]) -> bool {
    qualifiers
        .iter()
        .any(|qualifier| matches!(qualifier, Qualifier::Storage(name) if name == "const"))
}
fn value_copy(value: &ConstantValue) -> ConstantValue {
    match value {
        ConstantValue::Bool(value) => ConstantValue::Bool(*value),
        ConstantValue::Int(value) => ConstantValue::Int(*value),
        ConstantValue::UInt(value) => ConstantValue::UInt(*value),
        ConstantValue::FloatBits(value) => ConstantValue::FloatBits(*value),
    }
}
pub fn run(ast: &Ast, stage: Stage) -> Result<TypedModule, CompileErrors> {
    Analyzer::new(stage).run(ast)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Options, Source, lex, parse, preprocess};
    fn analyze(text: &str) -> Result<TypedModule, CompileErrors> {
        let source = Source::new("semantic.glsl", text);
        let preprocessed = preprocess::run(&[source], &[], &Options::default())?;
        let tokens = lex::run(&preprocessed)?;
        let ast = parse::run(&tokens, Stage::Vertex)?;
        run(&ast, Stage::Vertex)
    }
    #[test]
    fn scopes_and_lvalues() {
        let scope_result = analyze("int value = 1; void main() { int local = value; local = 2; }");
        assert!(scope_result.is_ok(), "{scope_result:?}");
        assert!(analyze("void main() { 1 = 2; }").is_err());
    }
    #[test]
    fn lenient_calls_and_array_constants() {
        let result = analyze(
            "float make(float value) { return value; } int values[5]; void main() { float value = make(1); }",
        );
        assert!(result.is_ok(), "{result:?}");
    }
    #[test]
    fn duplicate_symbols_fail() {
        assert!(analyze("int value; int value; void main() {}").is_err());
    }

    #[test]
    fn scalar_broadcast_and_overload_conversion_are_typed() {
        let result = analyze(
            "void main() { vec3 value = vec3(1); value *= 2.0; value += 1; float x = abs(-1); }",
        );
        assert!(result.is_ok(), "{result:?}");
    }

    #[test]
    fn invalid_vector_operations_and_constructors_fail() {
        assert!(
            analyze("void main() { vec3 value = vec3(vec2(1.0)); value += bvec3(true); }").is_err()
        );
    }

    #[test]
    fn compatibility_variables_and_legacy_texture_overloads_are_builtins() {
        let result = analyze(
            "uniform sampler2D tex; void main() { gl_Position = ftransform(); gl_Color = texture2D(tex, gl_MultiTexCoord0.xy); gl_ModelViewMatrix[0] = gl_Color; }",
        );
        assert!(result.is_ok(), "{result:?}");
    }

    #[test]
    fn extended_builtins_cover_integer_vectors_and_iris_attributes() {
        let result = analyze(
            "void main() { uvec2 mid = mc_midTexCoord; vec4 tangent = at_tangent; ivec3 bits = bitCount(ivec3(1)); bvec3 equal = equal(ivec3(1), ivec3(2)); vec3 mixed = mix(vec3(0.0), vec3(1.0), bvec3(true)); }",
        );
        assert!(result.is_ok(), "{result:?}");
    }

    #[test]
    fn standard_numeric_matrix_and_bit_builtins_are_typed() {
        let result = analyze(
            "void main() { vec3 value = normalize(vec3(1.0)); mat3 matrix = inverse(mat3(1.0)); uint bits = floatBitsToUint(value.x); ivec3 count = bitCount(ivec3(1)); }",
        );
        assert!(result.is_ok(), "{result:?}");
    }

    #[test]
    fn stage_specific_compute_builtins_and_barriers_are_builtins() {
        let source = Source::new(
            "compute.glsl",
            "void main() { uint value = gl_GlobalInvocationID.x + gl_WorkGroupSize.x; barrier(); memoryBarrierImage(); }",
        );
        let preprocessed = preprocess::run(&[source], &[], &Options::default());
        assert!(preprocessed.is_ok(), "{preprocessed:?}");
        let preprocessed = match preprocessed {
            Ok(value) => value,
            Err(_) => return,
        };
        let tokens = lex::run(&preprocessed);
        assert!(tokens.is_ok(), "{tokens:?}");
        let tokens = match tokens {
            Ok(value) => value,
            Err(_) => return,
        };
        let ast = parse::run(&tokens, Stage::Compute);
        assert!(ast.is_ok(), "{ast:?}");
        let ast = match ast {
            Ok(value) => value,
            Err(_) => return,
        };
        let result = run(&ast, Stage::Compute);
        assert!(result.is_ok(), "{result:?}");
    }

    #[test]
    fn texture_sampling_forms_are_typed_for_dimensions_and_shadow_comparison() {
        let result = analyze(
            "uniform sampler2D tex; uniform sampler2DShadow shadow; uniform samplerCubeShadow cube_shadow; uniform sampler2DArray array_tex; uniform sampler3D volume; uniform isampler2D integer_tex; void main() { vec2 uv = vec2(0.5); vec4 sample0 = texture(tex, uv); vec4 sample1 = textureLod(tex, uv, 2.0); vec4 sample2 = textureGrad(tex, uv, dFdx(uv), dFdy(uv)); vec4 sample3 = textureProj(tex, vec3(uv, 1.0)); vec4 sample4 = textureOffset(tex, uv, ivec2(1)); vec4 sample5 = textureLodOffset(tex, uv, 1.0, ivec3(1)); vec4 sample6 = texelFetch(tex, ivec2(1), 0); vec4 gathered = textureGather(tex, uv, 2); vec2 queried = textureQueryLod(tex, uv); ivec2 size2 = textureSize(tex, 0); float shadow_value = texture(shadow, vec3(uv, 0.5)); float cube_value = texture(cube_shadow, vec4(0.0, 0.0, 1.0, 0.5)); vec4 array_value = texture(array_tex, vec3(uv, 0.0)); vec4 volume_value = textureLod(volume, vec3(uv, 0.0), 0.0); ivec4 integer_value = texelFetch(integer_tex, ivec2(0), 0); }",
        );
        assert!(result.is_ok(), "{result:?}");
    }

    #[test]
    fn extended_bit_conversion_builtins_are_typed() {
        let result = analyze(
            "void main() { uint bits = floatBitsToUint(1.0); vec3 value = uintBitsToFloat(uvec3(bits)); bool invalid = isnan(value.x); }",
        );
        assert!(result.is_ok(), "{result:?}");
    }
}
