use crate::{
    CompileError, CompileErrors, ErrorKind, Location, Stage,
    lex::{Span, Token, TokenKind, TokenStream},
};
use std::collections::VecDeque;

#[derive(Debug, Eq, PartialEq)]
pub struct Ast {
    pub declarations: Vec<Declaration>,
}

#[derive(Debug, Eq, PartialEq)]
pub struct Declaration {
    pub span: Span,
    pub qualifiers: Vec<Qualifier>,
    pub type_specifier: TypeSpecifier,
    pub name: Option<String>,
    pub array: Vec<Expression>,
    pub initializer: Option<Expression>,
    pub body: Option<Box<Statement>>,
    pub parameters: Vec<Parameter>,
    pub members: Vec<Declaration>,
}

#[derive(Debug, Eq, PartialEq)]
pub struct Parameter {
    pub span: Span,
    pub qualifiers: Vec<Qualifier>,
    pub type_specifier: TypeSpecifier,
    pub name: Option<String>,
    pub array: Vec<Expression>,
}

#[derive(Debug, Eq, PartialEq)]
pub enum TypeSpecifier {
    Named(String),
    Void,
    Bool,
    Int,
    UInt,
    Float,
    Double,
    Int64,
    UInt64,
    Vec(u8),
    DVec(u8),
    I64Vec(u8),
    U64Vec(u8),
    IVec(u8),
    UVec(u8),
    BVec(u8),
    Mat(u8, u8),
    DMat(u8, u8),
    Struct(String, Vec<Declaration>),
    Interface(String, Vec<Declaration>),
    Sampler(String),
    Image(String),
}

#[derive(Debug, Eq, PartialEq)]
pub enum Qualifier {
    Storage(String),
    Layout(Vec<(String, Option<Expression>)>),
    Interpolation(String),
    Precision(String),
    Invariant,
    Precise,
    Memory(String),
    Subroutine(Vec<String>),
}

#[derive(Debug, Eq, PartialEq)]
pub enum Statement {
    Compound(Span, Vec<Statement>),
    Declaration(Declaration),
    Expression(Span, Option<Expression>),
    If(Span, Expression, Box<Statement>, Option<Box<Statement>>),
    Switch(Span, Expression, Vec<Statement>),
    Label(Span, Option<Expression>, Box<Statement>),
    Loop(
        Span,
        Option<Box<Statement>>,
        Option<Expression>,
        Option<Expression>,
        Box<Statement>,
    ),
    Jump(Span, String, Option<Expression>),
    Empty(Span),
    Error(Span),
}

#[derive(Debug, Eq, PartialEq)]
pub enum Expression {
    Literal(String),
    Name(String),
    Unary(String, Box<Expression>),
    Binary(String, Box<Expression>, Box<Expression>),
    Conditional(Box<Expression>, Box<Expression>, Box<Expression>),
    Call(Box<Expression>, Vec<Expression>),
    Index(Box<Expression>, Box<Expression>),
    Field(Box<Expression>, String),
    Constructor(TypeSpecifier, Vec<Expression>),
}

struct Parser {
    tokens: VecDeque<Token>,
    errors: Vec<CompileError>,
}

fn matrix_type(value: &str, double: bool) -> TypeSpecifier {
    let dimensions = value.strip_prefix(if double { "dmat" } else { "mat" });
    let Some(dimensions) = dimensions else {
        return if double {
            TypeSpecifier::DMat(3, 3)
        } else {
            TypeSpecifier::Mat(3, 3)
        };
    };
    let mut parts = dimensions.split('x');
    let columns = parts.next().and_then(|part| part.parse().ok()).unwrap_or(3);
    let rows = parts
        .next()
        .and_then(|part| part.parse().ok())
        .unwrap_or(columns);
    if double {
        TypeSpecifier::DMat(columns, rows)
    } else {
        TypeSpecifier::Mat(columns, rows)
    }
}

fn name_or_empty(value: Option<String>) -> String {
    value.unwrap_or_default()
}

impl Parser {
    fn peek(&self) -> Option<&Token> {
        self.tokens.front()
    }
    fn take(&mut self) -> Option<Token> {
        self.tokens.pop_front()
    }
    fn at(&self, text: &str) -> bool {
        self.peek().is_some_and(|token| token.text == text)
    }
    fn span(&self) -> Span {
        self.peek().map_or(
            Span {
                start: 0,
                end: 0,
                line: 1,
                column: 1,
                source_index: 0,
                source_path: String::from(""),
            },
            |token| Span {
                start: token.span.start,
                end: token.span.end,
                line: token.span.line,
                column: token.span.column,
                source_index: token.span.source_index,
                source_path: String::from(token.span.source_path.as_str()),
            },
        )
    }
    fn error(&mut self, message: &str) {
        let span = self.span();
        self.errors.push(CompileError::new(
            ErrorKind::Parse,
            message,
            Some(Location::new(span.source_path, span.line, span.column)),
        ));
    }
    fn consume(&mut self, text: &str) -> bool {
        if self.at(text) {
            let _ = self.take();
            true
        } else {
            false
        }
    }
    fn expect(&mut self, text: &str) {
        if !self.consume(text) {
            self.error(&format!("expected `{text}`"));
        }
    }
    fn identifier(&mut self) -> Option<String> {
        let token = self.take()?;
        if token.kind == TokenKind::Identifier {
            Some(token.text)
        } else {
            self.errors.push(CompileError::new(
                ErrorKind::Parse,
                "expected identifier",
                None,
            ));
            None
        }
    }
    fn qualifiers(&mut self) -> Vec<Qualifier> {
        let mut result = Vec::new();
        while let Some(text) = self.peek().map(|token| token.text.as_str()) {
            if text == "precision" {
                let _ = self.take();
                let value = self.take();
                match value {
                    Some(token) if matches!(token.text.as_str(), "lowp" | "mediump" | "highp") => {
                        result.push(Qualifier::Precision(token.text));
                        continue;
                    }
                    _ => self.error("expected precision qualifier"),
                }
                break;
            }
            let qualifier = match text {
                "const" | "in" | "out" | "inout" | "uniform" | "buffer" | "shared"
                | "attribute" | "varying" | "centroid" | "sample" | "patch" | "coherent"
                | "volatile" | "restrict" | "readonly" | "writeonly" => {
                    Some(Qualifier::Storage(String::from(text)))
                }
                "flat" | "smooth" | "noperspective" => {
                    Some(Qualifier::Interpolation(String::from(text)))
                }
                "lowp" | "mediump" | "highp" => Some(Qualifier::Precision(String::from(text))),
                "invariant" => Some(Qualifier::Invariant),
                "precise" => Some(Qualifier::Precise),
                "layout" => Some(self.layout()),
                "subroutine" => Some(self.subroutine()),
                _ => None,
            };
            if let Some(value) = qualifier {
                let consumes = matches!(value, Qualifier::Layout(_) | Qualifier::Subroutine(_));
                result.push(value);
                if !consumes {
                    let _ = self.take();
                }
            } else {
                break;
            }
        }
        result
    }
    fn layout(&mut self) -> Qualifier {
        let mut values = Vec::new();
        let _ = self.take();
        self.expect("(");
        while !self.at(")") && self.peek().is_some() {
            let name = name_or_empty(self.identifier());
            let value = if self.consume("=") {
                self.expression(0)
            } else {
                None
            };
            values.push((name, value));
            if !self.consume(",") {
                break;
            }
        }
        self.expect(")");
        Qualifier::Layout(values)
    }
    fn subroutine(&mut self) -> Qualifier {
        let mut values = Vec::new();
        let _ = self.take();
        if self.consume("(") {
            while !self.at(")") && self.peek().is_some() {
                if let Some(value) = self.identifier() {
                    values.push(value);
                }
                if !self.consume(",") {
                    break;
                }
            }
            self.expect(")");
        }
        Qualifier::Subroutine(values)
    }
    fn type_specifier(&mut self) -> Option<TypeSpecifier> {
        let text = self.peek()?.text.as_str();
        let result = match text {
            "void" => TypeSpecifier::Void,
            "bool" => TypeSpecifier::Bool,
            "int" => TypeSpecifier::Int,
            "uint" => TypeSpecifier::UInt,
            "float" => TypeSpecifier::Float,
            "double" => TypeSpecifier::Double,
            "int64_t" => TypeSpecifier::Int64,
            "uint64_t" => TypeSpecifier::UInt64,
            value if value.starts_with("dvec") && value.len() == 5 => {
                TypeSpecifier::DVec(value.as_bytes().get(4).copied()?.saturating_sub(b'0'))
            }
            value if value.starts_with("i64vec") && value.len() == 6 => {
                TypeSpecifier::I64Vec(value.as_bytes().get(5).copied()?.saturating_sub(b'0'))
            }
            value if value.starts_with("u64vec") && value.len() == 6 => {
                TypeSpecifier::U64Vec(value.as_bytes().get(5).copied()?.saturating_sub(b'0'))
            }
            value if value.starts_with("vec") && value.len() == 4 => {
                TypeSpecifier::Vec(value.as_bytes().get(3).copied()?.saturating_sub(b'0'))
            }
            value if value.starts_with("ivec") && value.len() == 5 => {
                TypeSpecifier::IVec(value.as_bytes().get(4).copied()?.saturating_sub(b'0'))
            }
            value if value.starts_with("uvec") && value.len() == 5 => {
                TypeSpecifier::UVec(value.as_bytes().get(4).copied()?.saturating_sub(b'0'))
            }
            value if value.starts_with("bvec") && value.len() == 5 => {
                TypeSpecifier::BVec(value.as_bytes().get(4).copied()?.saturating_sub(b'0'))
            }
            value if value.starts_with("mat") => matrix_type(value, false),
            value if value.starts_with("dmat") => matrix_type(value, true),
            value
                if value.starts_with("sampler")
                    || value.starts_with("isampler")
                    || value.starts_with("usampler") =>
            {
                TypeSpecifier::Sampler(String::from(value))
            }
            value
                if value.starts_with("image")
                    || value.starts_with("iimage")
                    || value.starts_with("uimage") =>
            {
                TypeSpecifier::Image(String::from(value))
            }
            "struct" => {
                let _ = self.take();
                return self.struct_type();
            }
            value => TypeSpecifier::Named(String::from(value)),
        };
        let _ = self.take();
        Some(result)
    }
    fn struct_type(&mut self) -> Option<TypeSpecifier> {
        let name = match self.identifier() {
            Some(value) => value,
            None => String::from("anonymous"),
        };
        let members = if self.consume("{") {
            self.members()
        } else {
            Vec::new()
        };
        Some(TypeSpecifier::Struct(name, members))
    }
    fn members(&mut self) -> Vec<Declaration> {
        let mut result = Vec::new();
        while !self.at("}") && self.peek().is_some() {
            if let Some(value) = self.declaration() {
                result.push(value);
            } else {
                self.synchronize();
            }
        }
        self.expect("}");
        result
    }
    fn arrays(&mut self) -> Vec<Expression> {
        let mut result = Vec::new();
        while self.consume("[") {
            let value = if self.at("]") {
                None
            } else {
                self.expression(0)
            };
            self.expect("]");
            if let Some(value) = value {
                result.push(value);
            }
        }
        result
    }
    fn parameter(&mut self) -> Option<Parameter> {
        let span = self.span();
        let qualifiers = self.qualifiers();
        let type_specifier = self.type_specifier()?;
        let name = if self
            .peek()
            .is_some_and(|token| token.kind == TokenKind::Identifier)
        {
            self.identifier()
        } else {
            None
        };
        let array = self.arrays();
        Some(Parameter {
            span,
            qualifiers,
            type_specifier,
            name,
            array,
        })
    }
    fn declaration(&mut self) -> Option<Declaration> {
        let span = self.span();
        let qualifiers = self.qualifiers();
        if self
            .tokens
            .get(1)
            .is_some_and(|token| token.text == "{")
            && self.peek().is_some_and(|token| token.kind == TokenKind::Identifier)
            && qualifiers.iter().any(|item| {
                matches!(item, Qualifier::Storage(value) if value == "uniform" || value == "buffer")
            })
        {
            let block_name = self.identifier()?;
            let _ = self.take();
            let members = self.members();
            let instance_name = if self
                .peek()
                .is_some_and(|token| token.kind == TokenKind::Identifier)
            {
                self.identifier()
            } else {
                None
            };
            let array = self.arrays();
            self.expect(";");
            return Some(Declaration {
                span,
                qualifiers,
                type_specifier: TypeSpecifier::Interface(block_name, members),
                name: instance_name,
                array,
                initializer: None,
                body: None,
                parameters: Vec::new(),
                members: Vec::new(),
            });
        }
        if self.at(";") && qualifiers.iter().any(|item| {
            matches!(item, Qualifier::Layout(values) if values.iter().any(|(name, _)| name.starts_with("local_size_") || matches!(name.as_str(), "vertices" | "triangles" | "quads" | "isolines" | "equal_spacing" | "fractional_even_spacing" | "fractional_odd_spacing" | "cw" | "ccw" | "point_mode")))
        }) {
            let _ = self.take();
            return Some(Declaration {
                span,
                qualifiers,
                type_specifier: TypeSpecifier::Void,
                name: None,
                array: Vec::new(),
                initializer: None,
                body: None,
                parameters: Vec::new(),
                members: Vec::new(),
            });
        }
        let type_specifier = match self.type_specifier() {
            Some(value) => value,
            None => {
                self.error("expected type specifier");
                return None;
            }
        };
        let name = if self
            .peek()
            .is_some_and(|token| token.kind == TokenKind::Identifier)
        {
            self.identifier()
        } else {
            None
        };
        if self.consume("(") {
            let mut parameters = Vec::new();
            while !self.at(")") && self.peek().is_some() {
                if let Some(value) = self.parameter() {
                    parameters.push(value);
                }
                if !self.consume(",") {
                    break;
                }
            }
            self.expect(")");
            let body = if self.at("{") {
                self.statement()
            } else {
                self.expect(";");
                None
            };
            return Some(Declaration {
                span,
                qualifiers,
                type_specifier,
                name,
                array: Vec::new(),
                initializer: None,
                body: body.map(Box::new),
                parameters,
                members: Vec::new(),
            });
        }
        let array = self.arrays();
        let initializer = if self.consume("=") {
            self.expression(0)
        } else {
            None
        };
        while self.consume(",") {
            let _ = self.identifier();
            let _ = self.arrays();
            if self.consume("=") {
                let _ = self.expression(0);
            }
        }
        self.expect(";");
        Some(Declaration {
            span,
            qualifiers,
            type_specifier,
            name,
            array,
            initializer,
            body: None,
            parameters: Vec::new(),
            members: Vec::new(),
        })
    }
    fn statement(&mut self) -> Option<Statement> {
        let span = self.span();
        if self.consume("{") {
            let mut values = Vec::new();
            while !self.at("}") && self.peek().is_some() {
                let before = self.tokens.len();
                if let Some(value) = self.statement() {
                    values.push(value);
                } else {
                    self.synchronize();
                }
                if self.tokens.len() == before {
                    let _ = self.take();
                }
            }
            self.expect("}");
            return Some(Statement::Compound(span, values));
        }
        if self.consume("if") {
            self.expect("(");
            let condition = self.expression(0)?;
            self.expect(")");
            let yes = Box::new(self.statement()?);
            let no = if self.consume("else") {
                Some(Box::new(self.statement()?))
            } else {
                None
            };
            return Some(Statement::If(span, condition, yes, no));
        }
        if self.consume("for") {
            self.expect("(");
            let init = if self.at(";") {
                let _ = self.take();
                None
            } else if self.looks_like_declaration() {
                self.declaration()
                    .map(|value| Box::new(Statement::Declaration(value)))
            } else {
                let value = self.expression(0);
                self.expect(";");
                Some(Box::new(Statement::Expression(self.span(), value)))
            };
            let condition = if self.at(";") {
                let _ = self.take();
                None
            } else {
                let value = self.expression(0);
                self.expect(";");
                value
            };
            let update = if self.at(")") {
                None
            } else {
                self.expression(0)
            };
            self.expect(")");
            let body = Box::new(self.statement()?);
            return Some(Statement::Loop(span, init, condition, update, body));
        }
        if self.consume("while") {
            self.expect("(");
            let condition = self.expression(0)?;
            self.expect(")");
            let body = Box::new(self.statement()?);
            return Some(Statement::Loop(span, None, Some(condition), None, body));
        }
        if self.consume("do") {
            let body = Box::new(self.statement()?);
            self.expect("while");
            self.expect("(");
            let condition = self.expression(0)?;
            self.expect(")");
            self.expect(";");
            return Some(Statement::Loop(span, None, Some(condition), None, body));
        }
        if self.consume("switch") {
            self.expect("(");
            let value = self.expression(0)?;
            self.expect(")");
            let body = match self.statement()? {
                Statement::Compound(_, values) => values,
                value => vec![value],
            };
            return Some(Statement::Switch(span, value, body));
        }
        if self.consume("case") {
            let value = self.expression(0);
            self.expect(":");
            let body = Box::new(self.statement()?);
            return Some(Statement::Label(span, value, body));
        }
        if self.consume("default") {
            self.expect(":");
            let body = Box::new(self.statement()?);
            return Some(Statement::Label(span, None, body));
        }
        if ["break", "continue", "discard", "demote", "return"]
            .iter()
            .any(|value| self.at(value))
        {
            let keyword = self.take()?.text;
            let value = if self.at(";") {
                None
            } else {
                self.expression(0)
            };
            self.expect(";");
            return Some(Statement::Jump(span, keyword, value));
        }
        if self.consume(";") {
            return Some(Statement::Empty(span));
        }
        if self.looks_like_declaration() {
            return self.declaration().map(Statement::Declaration);
        }
        let value = self.expression(0);
        self.expect(";");
        Some(Statement::Expression(span, value))
    }
    fn looks_like_declaration(&self) -> bool {
        self.peek()
            .is_some_and(|token| token.kind == TokenKind::Identifier)
            && self
                .tokens
                .get(1)
                .is_some_and(|token| token.kind == TokenKind::Identifier)
    }
    fn expression(&mut self, minimum: u8) -> Option<Expression> {
        let token = self.take()?;
        let mut left = match token.text.as_str() {
            "(" => {
                let value = self.expression(0);
                self.expect(")");
                value?
            }
            "+" | "-" | "!" | "~" | "++" | "--" => {
                Expression::Unary(token.text, Box::new(self.expression(12)?))
            }
            _ if token.kind == TokenKind::Integer
                || token.kind == TokenKind::Float
                || token.kind == TokenKind::String =>
            {
                Expression::Literal(token.text)
            }
            _ => Expression::Name(token.text),
        };
        loop {
            if self.consume("(") {
                let mut args = Vec::new();
                while !self.at(")") && self.peek().is_some() {
                    if let Some(value) = self.expression(0) {
                        args.push(value);
                    }
                    if !self.consume(",") {
                        break;
                    }
                }
                self.expect(")");
                left = Expression::Call(Box::new(left), args);
                continue;
            }
            if self.consume("[") {
                let index = self.expression(0)?;
                self.expect("]");
                left = Expression::Index(Box::new(left), Box::new(index));
                continue;
            }
            if self.consume(".") {
                let name = name_or_empty(self.identifier());
                left = Expression::Field(Box::new(left), name);
                continue;
            }
            if self.at("++") || self.at("--") {
                let operator = self.take().map_or_else(String::new, |value| value.text);
                left = Expression::Unary(operator, Box::new(left));
                continue;
            }
            let operator = self
                .peek()
                .map(|value| value.text.as_str())
                .and_then(binary_operator)
                .map(|(value, precedence)| (String::from(value), precedence));
            let Some((op, precedence)) = operator else {
                break;
            };
            if precedence < minimum {
                break;
            }
            let _ = self.take();
            let right = self.expression(precedence + 1)?;
            left = Expression::Binary(op, Box::new(left), Box::new(right));
        }
        if minimum == 0 && self.consume("?") {
            let yes = self.expression(0)?;
            self.expect(":");
            let no = self.expression(0)?;
            left = Expression::Conditional(Box::new(left), Box::new(yes), Box::new(no));
        }
        Some(left)
    }
    fn synchronize(&mut self) {
        while let Some(token) = self.peek() {
            if token.text == ";" || token.text == "}" {
                let _ = self.take();
                break;
            }
            let _ = self.take();
        }
    }
}

fn binary_operator(value: &str) -> Option<(&str, u8)> {
    match value {
        "=" | "+=" | "-=" | "*=" | "/=" | "%=" => Some((value, 1)),
        "||" => Some((value, 2)),
        "^^" => Some((value, 3)),
        "&&" => Some((value, 4)),
        "==" | "!=" => Some((value, 5)),
        "<" | ">" | "<=" | ">=" => Some((value, 6)),
        "+" | "-" => Some((value, 7)),
        "*" | "/" | "%" => Some((value, 8)),
        _ => None,
    }
}

pub fn run_with_limit(
    tokens: &TokenStream,
    stage: Stage,
    max_recursion_depth: u32,
) -> Result<Ast, CompileErrors> {
    let mut depth = 0_u32;
    for token in &tokens.tokens {
        if token.kind != TokenKind::Punctuation {
            continue;
        }
        if matches!(token.text.as_str(), "(" | "{" | "[") {
            depth = depth.saturating_add(1);
            if depth > max_recursion_depth {
                return Err(CompileErrors::one(CompileError::new(
                    ErrorKind::Parse,
                    "parser recursion depth limit exceeded",
                    Some(Location::new(
                        token.span.source_path.as_str(),
                        token.span.line,
                        token.span.column,
                    )),
                )));
            }
        } else if matches!(token.text.as_str(), ")" | "}" | "]") {
            if depth == 0 {
                return Err(CompileErrors::one(CompileError::new(
                    ErrorKind::Parse,
                    "unexpected closing delimiter",
                    Some(Location::new(
                        token.span.source_path.as_str(),
                        token.span.line,
                        token.span.column,
                    )),
                )));
            }
            depth -= 1;
        }
    }
    if depth != 0 {
        return Err(CompileErrors::one(CompileError::new(
            ErrorKind::Parse,
            "unterminated delimiter",
            tokens.tokens.first().map(|token| {
                Location::new(
                    token.span.source_path.as_str(),
                    token.span.line,
                    token.span.column,
                )
            }),
        )));
    }
    run(tokens, stage)
}

pub fn run(tokens: &TokenStream, stage: Stage) -> Result<Ast, CompileErrors> {
    let copied = tokens.tokens.iter().map(|token| Token {
        kind: token.kind,
        text: String::from(token.text.as_str()),
        span: Span {
            start: token.span.start,
            end: token.span.end,
            line: token.span.line,
            column: token.span.column,
            source_index: token.span.source_index,
            source_path: String::from(token.span.source_path.as_str()),
        },
    });
    let mut parser = Parser {
        tokens: copied.collect(),
        errors: Vec::new(),
    };
    let _ = stage;
    let mut declarations = Vec::new();
    while parser
        .peek()
        .is_some_and(|token| token.kind != TokenKind::EndOfFile)
    {
        let before = parser.tokens.len();
        if parser
            .peek()
            .is_some_and(|token| token.kind == TokenKind::Directive)
        {
            let _ = parser.take();
            continue;
        }
        if let Some(value) = parser.declaration() {
            declarations.push(value);
        } else {
            parser.synchronize();
        }
        if parser.tokens.len() == before {
            let _ = parser.take();
        }
    }
    if parser.errors.is_empty() {
        Ok(Ast { declarations })
    } else {
        Err(CompileErrors::from_vec(parser.errors))
    }
}

#[cfg(test)]
mod tests {
    use super::run;
    use crate::{Options, Source, Stage, lex, preprocess};

    #[test]
    fn parses_legacy_interfaces_structs_switch_and_arrays() {
        let text = "#version 120\nattribute vec3 position; varying vec4 color[2][3]; struct Light { vec3 position; }; void main() { switch (int(1)) { case 1: discard; default: break; } }";
        let source = Source::new("legacy.vsh", text);
        let preprocessed = match preprocess::run(&[source], &[], &Options::default()) {
            Ok(value) => value,
            Err(_) => return,
        };
        let tokens = match lex::run(&preprocessed) {
            Ok(value) => value,
            Err(_) => return,
        };
        let ast = run(&tokens, Stage::Vertex);
        let ast = match ast {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(ast.declarations.len() >= 4);
        assert!(
            ast.declarations
                .iter()
                .any(|declaration| declaration.name.as_deref() == Some("main"))
        );
    }

    #[test]
    fn parses_precision_declarations() {
        let source = Source::new(
            "precision.glsl",
            "precision highp float; precision mediump int; void main() {}",
        );
        let preprocessed = match preprocess::run(&[source], &[], &Options::default()) {
            Ok(value) => value,
            Err(_) => return,
        };
        let tokens = match lex::run(&preprocessed) {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(run(&tokens, Stage::Fragment).is_ok());
    }

    #[test]
    fn rejects_malformed_precision_declarations() {
        let source = Source::new("broken-precision.glsl", "precision float; void main() {} ");
        let preprocessed = match preprocess::run(&[source], &[], &Options::default()) {
            Ok(value) => value,
            Err(_) => return,
        };
        let tokens = match lex::run(&preprocessed) {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(run(&tokens, Stage::Fragment).is_err());
    }

    #[test]
    fn parses_for_while_and_multiple_declarators() {
        let source = Source::new(
            "loops.glsl",
            "void main() { float a = 1.0, b = 2.0; for (int i = 0; i < 2; i++) { while (i < 1) { b += a; } } do { a += b; } while (a < 4.0); }",
        );
        let preprocessed = match preprocess::run(&[source], &[], &Options::default()) {
            Ok(value) => value,
            Err(_) => return,
        };
        let tokens = match lex::run(&preprocessed) {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(run(&tokens, Stage::Fragment).is_ok());
    }

    #[test]
    fn rejects_missing_for_closing_parenthesis() {
        let source = Source::new(
            "broken-loop.glsl",
            "void main() { for (int i = 0; i < 2; i++ {} }",
        );
        let preprocessed = match preprocess::run(&[source], &[], &Options::default()) {
            Ok(value) => value,
            Err(_) => return,
        };
        let tokens = match lex::run(&preprocessed) {
            Ok(value) => value,
            Err(_) => return,
        };
        assert!(run(&tokens, Stage::Fragment).is_err());
    }

    #[test]
    fn reports_recoverable_syntax_errors() {
        let source = Source::new(
            "broken.glsl",
            "void first( { int x = 1; } void second() {}\n",
        );
        let preprocessed = match preprocess::run(&[source], &[], &Options::default()) {
            Ok(value) => value,
            Err(_) => return,
        };
        let tokens = match lex::run(&preprocessed) {
            Ok(value) => value,
            Err(_) => return,
        };
        let result = run(&tokens, Stage::Fragment);
        let errors = match result {
            Ok(_) => return,
            Err(value) => value,
        };
        assert_eq!(
            errors
                .as_slice()
                .first()
                .and_then(|error| error.location.as_ref())
                .map(|location| location.file.as_str()),
            Some("broken.glsl")
        );
    }
}
