use crate::{
    Stage,
    sema::{Field, FunctionSignature, Scalar, Type},
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BuiltinType {
    Void,
    Bool,
    Int,
    UInt,
    Int64,
    UInt64,
    Float,
    Double,
    Vec(u8),
    IVec(u8),
    UVec(u8),
    BVec(u8),
    Mat(u8, u8),
    DMat(u8, u8),
    Sampler(&'static str),
    Image(&'static str),
    Array(&'static BuiltinType, u8),
    Struct(&'static str),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Variable {
    pub name: &'static str,
    pub ty: BuiltinType,
    pub stages: u8,
}

const VERTEX: u8 = 1;
const FRAGMENT: u8 = 2;
const COMPUTE: u8 = 4;
const GEOMETRY: u8 = 8;
const TESSELLATION: u8 = 16;
const ALL_GRAPHICS: u8 = VERTEX | FRAGMENT | GEOMETRY | TESSELLATION;
const ALL: u8 = ALL_GRAPHICS | COMPUTE;

const VEC4: BuiltinType = BuiltinType::Vec(4);
const FLOAT: BuiltinType = BuiltinType::Float;

pub fn variables(stage: Stage) -> Vec<(&'static str, Type)> {
    let mask = stage_mask(stage);
    let mut result: Vec<(&'static str, Type)> = VARIABLE_TABLE
        .iter()
        .filter(|item| item.stages & mask != 0)
        .map(|item| (item.name, materialize(item.ty)))
        .collect();
    if mask & VERTEX != 0 {
        result.push(("gl_MultiTexCoord0", materialize(BuiltinType::Vec(4))));
        result.push(("gl_MultiTexCoord1", materialize(BuiltinType::Vec(4))));
        result.push(("gl_MultiTexCoord2", materialize(BuiltinType::Vec(4))));
        result.push(("gl_MultiTexCoord3", materialize(BuiltinType::Vec(4))));
    }
    if mask & FRAGMENT != 0 {
        result.push(("gl_FragData", materialize(BuiltinType::Array(&VEC4, 8))));
    }
    if mask & ALL_GRAPHICS != 0 {
        result.extend(matrix_variables());
        result.push(("gl_Fog", fog_type()));
    }
    if mask & VERTEX != 0 {
        result.push(("mc_Entity", materialize(BuiltinType::UInt)));
        result.push(("mc_midTexCoord", materialize(BuiltinType::UVec(2))));
        result.push(("at_tangent", materialize(BuiltinType::Vec(4))));
        result.push(("at_midBlock", materialize(BuiltinType::Vec(4))));
        result.push(("at_velocity", materialize(BuiltinType::Vec(3))));
        result.push((
            "gl_ClipDistance",
            materialize(BuiltinType::Array(&FLOAT, 8)),
        ));
        result.push((
            "gl_CullDistance",
            materialize(BuiltinType::Array(&FLOAT, 8)),
        ));
        result.push(("gl_BaseVertex", Type::Int));
        result.push(("gl_BaseInstance", Type::Int));
        result.push(("gl_DrawID", Type::Int));
    }
    if mask & FRAGMENT != 0 {
        result.push(("gl_SecondaryFragColor", materialize(BuiltinType::Vec(4))));
    }
    if mask & COMPUTE != 0 {
        result.push(("gl_NumWorkGroups", materialize(BuiltinType::UVec(3))));
    }
    if mask & TESSELLATION != 0 {
        let point = Type::Struct {
            name: String::from("gl_PerVertex"),
            fields: vec![Field {
                name: String::from("gl_Position"),
                ty: Type::Vector {
                    scalar: Scalar::Float,
                    size: 4,
                },
            }],
        };
        result.push((
            "gl_in",
            Type::Array {
                element: Box::new(Type::Struct {
                    name: String::from("gl_PerVertex"),
                    fields: vec![Field {
                        name: String::from("gl_Position"),
                        ty: Type::Vector {
                            scalar: Scalar::Float,
                            size: 4,
                        },
                    }],
                }),
                size: None,
            },
        ));
        result.push((
            "gl_out",
            Type::Array {
                element: Box::new(point),
                size: None,
            },
        ));
    }
    result
}

pub fn functions(stage: Stage) -> Vec<FunctionSignature> {
    let mut result = Vec::new();
    for name in [
        "radians",
        "degrees",
        "sin",
        "cos",
        "tan",
        "asin",
        "acos",
        "sinh",
        "cosh",
        "tanh",
        "asinh",
        "acosh",
        "atanh",
        "exp",
        "log",
        "exp2",
        "log2",
        "sqrt",
        "inversesqrt",
        "abs",
        "sign",
        "floor",
        "trunc",
        "round",
        "roundEven",
        "ceil",
        "fract",
    ] {
        unary_numeric(&mut result, name);
    }
    for name in ["atan", "pow", "mod", "min", "max", "step"] {
        binary_numeric(&mut result, name);
    }
    for name in ["clamp", "mix", "smoothstep"] {
        ternary_numeric(&mut result, name);
    }
    for name in ["dot", "distance"] {
        for size in 2..=4 {
            let ty = BuiltinType::Vec(size);
            add(&mut result, name, BuiltinType::Float, &[ty, ty]);
        }
        add(
            &mut result,
            name,
            BuiltinType::Double,
            &[BuiltinType::DMat(2, 2), BuiltinType::DMat(2, 2)],
        );
    }
    for name in ["cross", "reflect", "refract"] {
        for size in 2..=4 {
            let ty = BuiltinType::Vec(size);
            add(&mut result, name, ty, &[ty, ty]);
        }
    }
    for size in 2..=4 {
        let v = BuiltinType::Vec(size);
        let i = BuiltinType::IVec(size);
        let u = BuiltinType::UVec(size);
        let b = BuiltinType::BVec(size);
        for name in ["length", "normalize"] {
            add(
                &mut result,
                name,
                if name == "length" {
                    BuiltinType::Float
                } else {
                    v
                },
                &[v],
            );
        }
        for name in [
            "lessThan",
            "lessThanEqual",
            "greaterThan",
            "greaterThanEqual",
            "equal",
            "notEqual",
        ] {
            add(&mut result, name, b, &[v, v]);
        }
        add(&mut result, "not", b, &[b]);
        add(&mut result, "uaddCarry", u, &[u, u]);
        add(&mut result, "usubBorrow", u, &[u, u]);
        let _ = i;
    }
    add(
        &mut result,
        "any",
        BuiltinType::Bool,
        &[BuiltinType::BVec(2)],
    );
    add(
        &mut result,
        "any",
        BuiltinType::Bool,
        &[BuiltinType::BVec(3)],
    );
    add(
        &mut result,
        "any",
        BuiltinType::Bool,
        &[BuiltinType::BVec(4)],
    );
    add(
        &mut result,
        "all",
        BuiltinType::Bool,
        &[BuiltinType::BVec(2)],
    );
    add(
        &mut result,
        "all",
        BuiltinType::Bool,
        &[BuiltinType::BVec(3)],
    );
    add(
        &mut result,
        "all",
        BuiltinType::Bool,
        &[BuiltinType::BVec(4)],
    );
    for name in [
        "dFdx",
        "dFdy",
        "fwidth",
        "dFdxFine",
        "dFdyFine",
        "fwidthFine",
        "dFdxCoarse",
        "dFdyCoarse",
        "fwidthCoarse",
    ] {
        unary_numeric(&mut result, name);
    }
    matrix_functions(&mut result);
    pack_functions(&mut result);
    texture_functions(&mut result);
    numeric_conversion_functions(&mut result);
    geometric_functions(&mut result);
    bit_functions(&mut result);
    integer_functions(&mut result, stage);
    atomic_functions(&mut result);
    extended_functions(&mut result);
    result
}

fn atomic_functions(result: &mut Vec<FunctionSignature>) {
    for name in ["atomicAdd", "atomicExchange"] {
        for ty in [
            BuiltinType::Int,
            BuiltinType::UInt,
            BuiltinType::Float,
            BuiltinType::Double,
            BuiltinType::Int64,
            BuiltinType::UInt64,
        ] {
            add(result, name, ty, &[ty, ty]);
        }
    }
}

fn extended_functions(result: &mut Vec<FunctionSignature>) {
    for name in ["isnan", "isinf"] {
        add(result, name, BuiltinType::Bool, &[BuiltinType::Float]);
        for size in 2..=4 {
            add(
                result,
                name,
                BuiltinType::BVec(size),
                &[BuiltinType::Vec(size)],
            );
        }
    }
    for name in ["floatBitsToInt", "floatBitsToUint"] {
        let scalar = if name.ends_with("Int") {
            BuiltinType::Int
        } else {
            BuiltinType::UInt
        };
        add(result, name, scalar, &[BuiltinType::Float]);
        for size in 2..=4 {
            add(
                result,
                name,
                if name.ends_with("Int") {
                    BuiltinType::IVec(size)
                } else {
                    BuiltinType::UVec(size)
                },
                &[BuiltinType::Vec(size)],
            );
        }
    }
    for name in ["intBitsToFloat", "uintBitsToFloat"] {
        let source = if name.starts_with('i') {
            BuiltinType::Int
        } else {
            BuiltinType::UInt
        };
        add(result, name, BuiltinType::Float, &[source]);
        for size in 2..=4 {
            add(
                result,
                name,
                BuiltinType::Vec(size),
                &[if name.starts_with('i') {
                    BuiltinType::IVec(size)
                } else {
                    BuiltinType::UVec(size)
                }],
            );
        }
    }
    for name in ["bitCount", "findLSB", "findMSB"] {
        add(result, name, BuiltinType::Int, &[BuiltinType::Int]);
        add(result, name, BuiltinType::Int, &[BuiltinType::UInt]);
        for size in 2..=4 {
            add(
                result,
                name,
                BuiltinType::IVec(size),
                &[BuiltinType::IVec(size)],
            );
            add(
                result,
                name,
                BuiltinType::IVec(size),
                &[BuiltinType::UVec(size)],
            );
        }
    }
    for size in 2..=4 {
        let f = BuiltinType::Vec(size);
        let i = BuiltinType::IVec(size);
        let u = BuiltinType::UVec(size);
        for name in [
            "equal",
            "notEqual",
            "lessThan",
            "lessThanEqual",
            "greaterThan",
            "greaterThanEqual",
        ] {
            add(result, name, BuiltinType::BVec(size), &[i, i]);
            add(result, name, BuiltinType::BVec(size), &[u, u]);
        }
        for name in ["min", "max", "mod", "clamp"] {
            add(result, name, i, &[i, i]);
            add(result, name, u, &[u, u]);
            if name == "clamp" {
                add(result, name, i, &[i, i, i]);
                add(result, name, u, &[u, u, u]);
            }
        }
        add(result, "mod", f, &[f, BuiltinType::Float]);
        add(result, "mod", f, &[f, f]);
        add(result, "mix", f, &[f, f, BuiltinType::BVec(size)]);
        add(result, "mix", f, &[f, f, BuiltinType::IVec(size)]);
        add(result, "mix", f, &[f, f, BuiltinType::UVec(size)]);
    }
    for name in ["uaddCarry", "usubBorrow"] {
        for size in 2..=4 {
            let vector = BuiltinType::UVec(size);
            add(result, name, vector, &[vector, vector]);
        }
    }
    add(
        result,
        "umulExtended",
        BuiltinType::Void,
        &[
            BuiltinType::UInt,
            BuiltinType::UInt,
            BuiltinType::UInt,
            BuiltinType::UInt,
        ],
    );
    add(
        result,
        "imulExtended",
        BuiltinType::Void,
        &[
            BuiltinType::Int,
            BuiltinType::Int,
            BuiltinType::Int,
            BuiltinType::Int,
        ],
    );
    for size in 2..=4 {
        let f = BuiltinType::Vec(size);
        let m = BuiltinType::Mat(size, size);
        add(result, "matrixCompMult", m, &[m, m]);
        add(result, "transpose", m, &[m]);
        add(result, "outerProduct", m, &[f, f]);
    }
}

fn unary_numeric(result: &mut Vec<FunctionSignature>, name: &str) {
    for ty in [
        BuiltinType::Float,
        BuiltinType::Vec(2),
        BuiltinType::Vec(3),
        BuiltinType::Vec(4),
        BuiltinType::Double,
        BuiltinType::DMat(2, 2),
        BuiltinType::DMat(3, 3),
        BuiltinType::DMat(4, 4),
    ] {
        add(result, name, ty, &[ty]);
    }
}

fn binary_numeric(result: &mut Vec<FunctionSignature>, name: &str) {
    for ty in [
        BuiltinType::Float,
        BuiltinType::Vec(2),
        BuiltinType::Vec(3),
        BuiltinType::Vec(4),
        BuiltinType::Double,
        BuiltinType::Int,
        BuiltinType::IVec(2),
        BuiltinType::IVec(3),
        BuiltinType::IVec(4),
        BuiltinType::UInt,
        BuiltinType::UVec(2),
        BuiltinType::UVec(3),
        BuiltinType::UVec(4),
    ] {
        add(result, name, ty, &[ty, ty]);
    }
    for size in 2..=4 {
        let v = BuiltinType::Vec(size);
        add(result, name, v, &[v, BuiltinType::Float]);
        add(result, name, v, &[BuiltinType::Float, v]);
    }
}

fn ternary_numeric(result: &mut Vec<FunctionSignature>, name: &str) {
    for ty in [
        BuiltinType::Float,
        BuiltinType::Vec(2),
        BuiltinType::Vec(3),
        BuiltinType::Vec(4),
        BuiltinType::Double,
        BuiltinType::Int,
        BuiltinType::UInt,
    ] {
        add(result, name, ty, &[ty, ty, ty]);
    }
    for size in 2..=4 {
        let v = BuiltinType::Vec(size);
        add(
            result,
            name,
            v,
            &[v, BuiltinType::Float, BuiltinType::Float],
        );
        add(result, name, v, &[v, v, BuiltinType::Float]);
        add(result, name, v, &[BuiltinType::Float, v, v]);
    }
}

fn matrix_functions(result: &mut Vec<FunctionSignature>) {
    for size in 2..=4 {
        let matrix = BuiltinType::Mat(size, size);
        let vector = BuiltinType::Vec(size);
        add(result, "matrixCompMult", matrix, &[matrix, matrix]);
        add(result, "outerProduct", matrix, &[vector, vector]);
        add(result, "transpose", matrix, &[matrix]);
        add(result, "determinant", BuiltinType::Float, &[matrix]);
        add(result, "inverse", matrix, &[matrix]);
    }
}

fn pack_functions(result: &mut Vec<FunctionSignature>) {
    add(
        result,
        "packUnorm2x16",
        BuiltinType::UInt,
        &[BuiltinType::Vec(2)],
    );
    add(
        result,
        "packSnorm2x16",
        BuiltinType::UInt,
        &[BuiltinType::Vec(2)],
    );
    add(
        result,
        "packUnorm4x8",
        BuiltinType::UInt,
        &[BuiltinType::Vec(4)],
    );
    add(
        result,
        "packSnorm4x8",
        BuiltinType::UInt,
        &[BuiltinType::Vec(4)],
    );
    add(
        result,
        "unpackUnorm2x16",
        BuiltinType::Vec(2),
        &[BuiltinType::UInt],
    );
    add(
        result,
        "unpackSnorm2x16",
        BuiltinType::Vec(2),
        &[BuiltinType::UInt],
    );
    add(
        result,
        "unpackUnorm4x8",
        BuiltinType::Vec(4),
        &[BuiltinType::UInt],
    );
    add(
        result,
        "unpackSnorm4x8",
        BuiltinType::Vec(4),
        &[BuiltinType::UInt],
    );
    add(
        result,
        "packHalf2x16",
        BuiltinType::UInt,
        &[BuiltinType::Vec(2)],
    );
    add(
        result,
        "unpackHalf2x16",
        BuiltinType::Vec(2),
        &[BuiltinType::UInt],
    );
}

fn texture_functions(result: &mut Vec<FunctionSignature>) {
    for sampler in [
        "sampler1D",
        "sampler2D",
        "sampler3D",
        "samplerCube",
        "sampler1DShadow",
        "sampler2DShadow",
        "samplerCubeShadow",
        "sampler2DRect",
        "sampler1DArray",
        "sampler1DArrayShadow",
        "sampler2DArray",
        "sampler2DArrayShadow",
        "sampler2DMS",
        "sampler2DMSArray",
        "samplerCubeArray",
        "samplerCubeArrayShadow",
        "samplerBuffer",
        "isampler1D",
        "isampler2D",
        "isampler3D",
        "isamplerCube",
        "isampler1DArray",
        "isampler2DArray",
        "isampler2DMS",
        "isampler2DMSArray",
        "isamplerBuffer",
        "usampler1D",
        "usampler2D",
        "usampler3D",
        "usamplerCube",
        "usampler1DArray",
        "usampler2DArray",
        "usampler2DMS",
        "usampler2DMSArray",
        "usamplerBuffer",
    ] {
        let shadow = sampler.contains("Shadow");
        let integer = sampler.starts_with('i') || sampler.starts_with('u');
        let result_type = if shadow {
            BuiltinType::Float
        } else if sampler.starts_with('u') {
            BuiltinType::UVec(4)
        } else if integer {
            BuiltinType::IVec(4)
        } else {
            BuiltinType::Vec(4)
        };
        let coord = if sampler.contains("Cube") && shadow {
            BuiltinType::Vec(4)
        } else if sampler.contains("Cube") || sampler.contains("Array") || shadow {
            BuiltinType::Vec(3)
        } else if sampler.contains("1D") {
            BuiltinType::Float
        } else {
            BuiltinType::Vec(2)
        };
        let source = BuiltinType::Sampler(sampler);
        for name in ["texture", "textureGather", "texelFetch"] {
            add(result, name, result_type, &[source, coord]);
        }
        for projected_coord in [BuiltinType::Vec(3), BuiltinType::Vec(4)] {
            add(
                result,
                "textureProj",
                result_type,
                &[source, projected_coord],
            );
        }
        for name in ["textureLod", "textureProjLod"] {
            add(
                result,
                name,
                result_type,
                &[source, coord, BuiltinType::Float],
            );
        }
        for name in ["textureGrad", "textureProjGrad"] {
            add(result, name, result_type, &[source, coord, coord, coord]);
        }
        for name in [
            "textureOffset",
            "textureProjOffset",
            "textureGatherOffset",
            "texelFetchOffset",
        ] {
            add(
                result,
                name,
                result_type,
                &[source, coord, BuiltinType::IVec(2)],
            );
        }
        for name in ["textureLodOffset", "textureProjLodOffset"] {
            add(
                result,
                name,
                result_type,
                &[source, coord, BuiltinType::Float, BuiltinType::IVec(2)],
            );
        }
        for name in ["textureGradOffset", "textureProjGradOffset"] {
            add(
                result,
                name,
                result_type,
                &[source, coord, coord, coord, BuiltinType::IVec(2)],
            );
        }
        add(
            result,
            "textureGatherOffsets",
            BuiltinType::Vec(4),
            &[source, coord, BuiltinType::IVec(2)],
        );
        add(
            result,
            "textureGather",
            BuiltinType::Vec(4),
            &[source, coord, BuiltinType::Int],
        );
        add(
            result,
            "textureGatherOffset",
            BuiltinType::Vec(4),
            &[source, coord, BuiltinType::Int, BuiltinType::IVec(2)],
        );
        add(
            result,
            "textureGatherOffsets",
            BuiltinType::Vec(4),
            &[source, coord, BuiltinType::Int, BuiltinType::IVec(2)],
        );
        add(
            result,
            "textureQueryLod",
            BuiltinType::Vec(2),
            &[source, coord],
        );
        for name in [
            "texture1D",
            "texture2D",
            "texture3D",
            "textureCube",
            "shadow1D",
            "shadow2D",
            "shadow3D",
            "shadowCube",
            "texture1DProj",
            "texture2DProj",
            "texture3DProj",
            "textureCubeProj",
        ] {
            add(result, name, result_type, &[source, coord]);
        }
        for name in [
            "texture1DLod",
            "texture2DLod",
            "texture3DLod",
            "textureCubeLod",
            "texture2DProjLod",
        ] {
            add(
                result,
                name,
                result_type,
                &[source, coord, BuiltinType::Float],
            );
        }
        for name in [
            "texture2DGrad",
            "texture2DGradARB",
            "texture2DProjGrad",
            "texture2DProjGradARB",
            "texture2DGradEXT",
            "texture2DProjGradEXT",
            "texture2DShadow",
            "shadow2DProj",
            "shadow2DProjLod",
            "shadow2DProjGrad",
            "texture2DRect",
            "texture2DRectProj",
        ] {
            add(result, name, result_type, &[source, coord, coord, coord]);
        }
        for name in [
            "texture2DOffset",
            "texture2DOffsetARB",
            "texture2DProjOffset",
        ] {
            add(
                result,
                name,
                result_type,
                &[source, coord, BuiltinType::IVec(2)],
            );
        }
        for name in ["texture2DLodOffset", "texture2DProjLodOffset"] {
            add(
                result,
                name,
                result_type,
                &[source, coord, BuiltinType::Float, BuiltinType::IVec(2)],
            );
        }
        for name in [
            "texture2DGradOffset",
            "texture2DProjGradOffset",
            "texture2DGradOffsetARB",
            "texture2DProjGradOffsetARB",
            "texture2DGradOffsetEXT",
            "texture2DProjGradOffsetEXT",
        ] {
            add(
                result,
                name,
                result_type,
                &[source, coord, coord, coord, BuiltinType::IVec(2)],
            );
        }
        for offset_size in [2, 3] {
            add(
                result,
                "textureOffset",
                result_type,
                &[source, coord, BuiltinType::IVec(offset_size)],
            );
            add(
                result,
                "textureProjOffset",
                result_type,
                &[source, coord, BuiltinType::IVec(offset_size)],
            );
            add(
                result,
                "textureLodOffset",
                result_type,
                &[
                    source,
                    coord,
                    BuiltinType::Float,
                    BuiltinType::IVec(offset_size),
                ],
            );
            add(
                result,
                "textureProjLodOffset",
                result_type,
                &[
                    source,
                    coord,
                    BuiltinType::Float,
                    BuiltinType::IVec(offset_size),
                ],
            );
            add(
                result,
                "texelFetchOffset",
                result_type,
                &[
                    source,
                    coord,
                    BuiltinType::Int,
                    BuiltinType::IVec(offset_size),
                ],
            );
        }
        add(
            result,
            "textureSize",
            texture_size_type(sampler),
            &[BuiltinType::Sampler(sampler), BuiltinType::Int],
        );
        add(
            result,
            "textureQueryLevels",
            BuiltinType::Int,
            &[BuiltinType::Sampler(sampler)],
        );
        add(
            result,
            "textureSamples",
            BuiltinType::Int,
            &[BuiltinType::Sampler(sampler)],
        );
    }
}

fn texture_size_type(sampler: &str) -> BuiltinType {
    if sampler.contains("Buffer") || sampler.contains("1D") && !sampler.contains("Array") {
        BuiltinType::Int
    } else if sampler.contains("3D") || sampler.contains("Array") {
        BuiltinType::IVec(3)
    } else {
        BuiltinType::IVec(2)
    }
}

fn numeric_conversion_functions(result: &mut Vec<FunctionSignature>) {
    for size in 2..=4 {
        let v = BuiltinType::Vec(size);
        let i = BuiltinType::IVec(size);
        let u = BuiltinType::UVec(size);
        add(result, "floatBitsToInt", i, &[v]);
        add(result, "floatBitsToUint", u, &[v]);
        add(result, "intBitsToFloat", v, &[i]);
        add(result, "uintBitsToFloat", v, &[u]);
    }
    add(
        result,
        "floatBitsToInt",
        BuiltinType::Int,
        &[BuiltinType::Float],
    );
    add(
        result,
        "floatBitsToUint",
        BuiltinType::UInt,
        &[BuiltinType::Float],
    );
    add(
        result,
        "intBitsToFloat",
        BuiltinType::Float,
        &[BuiltinType::Int],
    );
    add(
        result,
        "uintBitsToFloat",
        BuiltinType::Float,
        &[BuiltinType::UInt],
    );
    for name in ["isnan", "isinf"] {
        add(result, name, BuiltinType::Bool, &[BuiltinType::Float]);
        for size in 2..=4 {
            add(
                result,
                name,
                BuiltinType::BVec(size),
                &[BuiltinType::Vec(size)],
            );
        }
    }
}

fn geometric_functions(result: &mut Vec<FunctionSignature>) {
    for size in 2..=4 {
        let v = BuiltinType::Vec(size);
        add(result, "dot", BuiltinType::Float, &[v, v]);
        if size == 3 {
            add(
                result,
                "cross",
                BuiltinType::Vec(3),
                &[BuiltinType::Vec(3), BuiltinType::Vec(3)],
            );
        }
        add(result, "reflect", v, &[v, v]);
        add(result, "refract", v, &[v, v, BuiltinType::Float]);
    }
    add(
        result,
        "faceforward",
        BuiltinType::Float,
        &[BuiltinType::Float, BuiltinType::Float, BuiltinType::Float],
    );
    for size in 2..=4 {
        let v = BuiltinType::Vec(size);
        add(result, "faceforward", v, &[v, v, v]);
    }
}

fn bit_functions(result: &mut Vec<FunctionSignature>) {
    for ty in [
        BuiltinType::Int,
        BuiltinType::IVec(2),
        BuiltinType::IVec(3),
        BuiltinType::IVec(4),
        BuiltinType::UInt,
        BuiltinType::UVec(2),
        BuiltinType::UVec(3),
        BuiltinType::UVec(4),
    ] {
        let offset = match ty {
            BuiltinType::Int | BuiltinType::UInt => BuiltinType::Int,
            BuiltinType::IVec(size) | BuiltinType::UVec(size) => BuiltinType::IVec(size),
            _ => BuiltinType::Int,
        };
        add(result, "bitCount", ty, &[ty]);
        add(result, "bitfieldExtract", ty, &[ty, offset, offset]);
        add(result, "bitfieldInsert", ty, &[ty, ty, offset, offset]);
    }
}

fn integer_functions(result: &mut Vec<FunctionSignature>, stage: Stage) {
    for name in [
        "uaddCarry",
        "usubBorrow",
        "umulExtended",
        "imulExtended",
        "atomicCounterIncrement",
        "atomicCounterDecrement",
        "atomicCounter",
    ] {
        add(result, name, BuiltinType::Int, &[BuiltinType::Int]);
    }
    for ty in [
        BuiltinType::Int,
        BuiltinType::IVec(2),
        BuiltinType::IVec(3),
        BuiltinType::IVec(4),
        BuiltinType::UInt,
        BuiltinType::UVec(2),
        BuiltinType::UVec(3),
        BuiltinType::UVec(4),
    ] {
        for name in ["bitfieldReverse", "findLSB", "findMSB"] {
            add(result, name, ty, &[ty]);
        }
    }
    add(
        result,
        "bitfieldExtract",
        BuiltinType::Int,
        &[BuiltinType::Int, BuiltinType::Int, BuiltinType::Int],
    );
    add(
        result,
        "bitfieldExtract",
        BuiltinType::UInt,
        &[BuiltinType::UInt, BuiltinType::Int, BuiltinType::Int],
    );
    add(
        result,
        "bitfieldInsert",
        BuiltinType::Int,
        &[
            BuiltinType::Int,
            BuiltinType::Int,
            BuiltinType::Int,
            BuiltinType::Int,
        ],
    );
    add(
        result,
        "bitfieldInsert",
        BuiltinType::UInt,
        &[
            BuiltinType::UInt,
            BuiltinType::UInt,
            BuiltinType::Int,
            BuiltinType::Int,
        ],
    );
    add(
        result,
        "imageStore",
        BuiltinType::Void,
        &[
            BuiltinType::Image("image2D"),
            BuiltinType::IVec(2),
            BuiltinType::Vec(4),
        ],
    );
    for name in [
        "imageLoad",
        "imageAtomicAdd",
        "imageAtomicMin",
        "imageAtomicMax",
        "imageAtomicAnd",
        "imageAtomicOr",
        "imageAtomicXor",
        "imageAtomicExchange",
        "imageAtomicCompSwap",
    ] {
        add(
            result,
            name,
            BuiltinType::Vec(4),
            &[BuiltinType::Image("image2D"), BuiltinType::IVec(2)],
        );
    }
    if matches!(stage, Stage::Geometry) {
        add(result, "emitVertex", BuiltinType::Void, &[]);
        add(result, "endPrimitive", BuiltinType::Void, &[]);
        add(result, "EmitVertex", BuiltinType::Void, &[]);
        add(result, "EndPrimitive", BuiltinType::Void, &[]);
    }
    if stage == Stage::Compute {
        for name in [
            "barrier",
            "memoryBarrier",
            "memoryBarrierAtomicCounter",
            "memoryBarrierBuffer",
            "memoryBarrierImage",
            "memoryBarrierShared",
            "groupMemoryBarrier",
            "subgroupBarrier",
        ] {
            add(result, name, BuiltinType::Void, &[]);
        }
    }
    if stage == Stage::Vertex {
        add(result, "ftransform", BuiltinType::Vec(4), &[]);
    }
}

fn add(
    result: &mut Vec<FunctionSignature>,
    name: &str,
    return_type: BuiltinType,
    parameters: &[BuiltinType],
) {
    result.push(FunctionSignature {
        name: String::from(name),
        return_type: materialize(return_type),
        parameters: parameters.iter().map(|item| materialize(*item)).collect(),
    });
}

fn materialize(value: BuiltinType) -> Type {
    match value {
        BuiltinType::Void => Type::Void,
        BuiltinType::Bool => Type::Bool,
        BuiltinType::Int => Type::Int,
        BuiltinType::UInt => Type::UInt,
        BuiltinType::Int64 => Type::Int64,
        BuiltinType::UInt64 => Type::UInt64,
        BuiltinType::Float => Type::Float,
        BuiltinType::Double => Type::Double,
        BuiltinType::Vec(size) => Type::Vector {
            scalar: Scalar::Float,
            size,
        },
        BuiltinType::IVec(size) => Type::Vector {
            scalar: Scalar::Int,
            size,
        },
        BuiltinType::UVec(size) => Type::Vector {
            scalar: Scalar::UInt,
            size,
        },
        BuiltinType::BVec(size) => Type::Vector {
            scalar: Scalar::Bool,
            size,
        },
        BuiltinType::Mat(columns, rows) => Type::Matrix {
            scalar: Scalar::Float,
            columns,
            rows,
        },
        BuiltinType::DMat(columns, rows) => Type::Matrix {
            scalar: Scalar::Double,
            columns,
            rows,
        },
        BuiltinType::Sampler(name) => Type::Sampler(String::from(name)),
        BuiltinType::Image(name) => Type::Image(String::from(name)),
        BuiltinType::Array(element, size) => Type::Array {
            element: Box::new(materialize(*element)),
            size: Some(u64::from(size)),
        },
        BuiltinType::Struct(name) => Type::Struct {
            name: String::from(name),
            fields: Vec::<Field>::new(),
        },
    }
}

fn stage_mask(stage: Stage) -> u8 {
    match stage {
        Stage::Vertex => VERTEX,
        Stage::Fragment => FRAGMENT,
        Stage::Compute => COMPUTE,
        Stage::Geometry => GEOMETRY,
        Stage::TessellationControl | Stage::TessellationEvaluation => TESSELLATION,
    }
}

static VARIABLE_TABLE: &[Variable] = &[
    Variable {
        name: "gl_Vertex",
        ty: VEC4,
        stages: VERTEX,
    },
    Variable {
        name: "gl_Normal",
        ty: BuiltinType::Vec(3),
        stages: VERTEX,
    },
    Variable {
        name: "gl_Color",
        ty: VEC4,
        stages: VERTEX,
    },
    Variable {
        name: "gl_FrontColor",
        ty: VEC4,
        stages: VERTEX,
    },
    Variable {
        name: "gl_BackColor",
        ty: VEC4,
        stages: VERTEX,
    },
    Variable {
        name: "gl_SecondaryColor",
        ty: VEC4,
        stages: VERTEX,
    },
    Variable {
        name: "gl_FrontSecondaryColor",
        ty: VEC4,
        stages: VERTEX,
    },
    Variable {
        name: "gl_BackSecondaryColor",
        ty: VEC4,
        stages: VERTEX,
    },
    Variable {
        name: "gl_FogCoord",
        ty: FLOAT,
        stages: VERTEX,
    },
    Variable {
        name: "gl_ClipVertex",
        ty: VEC4,
        stages: VERTEX,
    },
    Variable {
        name: "gl_Position",
        ty: VEC4,
        stages: VERTEX | GEOMETRY | TESSELLATION,
    },
    Variable {
        name: "gl_PointSize",
        ty: FLOAT,
        stages: VERTEX | GEOMETRY,
    },
    Variable {
        name: "gl_VertexID",
        ty: BuiltinType::Int,
        stages: VERTEX,
    },
    Variable {
        name: "gl_InstanceID",
        ty: BuiltinType::Int,
        stages: VERTEX,
    },
    Variable {
        name: "gl_FragCoord",
        ty: VEC4,
        stages: FRAGMENT,
    },
    Variable {
        name: "gl_FrontFacing",
        ty: BuiltinType::Bool,
        stages: FRAGMENT,
    },
    Variable {
        name: "gl_PointCoord",
        ty: BuiltinType::Vec(2),
        stages: FRAGMENT,
    },
    Variable {
        name: "gl_FragColor",
        ty: VEC4,
        stages: FRAGMENT,
    },
    Variable {
        name: "gl_FragDepth",
        ty: FLOAT,
        stages: FRAGMENT,
    },
    Variable {
        name: "gl_SampleID",
        ty: BuiltinType::Int,
        stages: FRAGMENT,
    },
    Variable {
        name: "gl_SamplePosition",
        ty: BuiltinType::Vec(2),
        stages: FRAGMENT,
    },
    Variable {
        name: "gl_SampleMaskIn",
        ty: BuiltinType::IVec(1),
        stages: FRAGMENT,
    },
    Variable {
        name: "gl_PrimitiveID",
        ty: BuiltinType::Int,
        stages: FRAGMENT | GEOMETRY | TESSELLATION,
    },
    Variable {
        name: "gl_InvocationID",
        ty: BuiltinType::Int,
        stages: GEOMETRY | TESSELLATION,
    },
    Variable {
        name: "gl_Layer",
        ty: BuiltinType::Int,
        stages: GEOMETRY | TESSELLATION,
    },
    Variable {
        name: "gl_ViewportIndex",
        ty: BuiltinType::Int,
        stages: GEOMETRY | TESSELLATION,
    },
    Variable {
        name: "gl_TessCoord",
        ty: BuiltinType::Vec(3),
        stages: TESSELLATION,
    },
    Variable {
        name: "gl_WorkGroupID",
        ty: BuiltinType::UVec(3),
        stages: COMPUTE,
    },
    Variable {
        name: "gl_LocalInvocationID",
        ty: BuiltinType::UVec(3),
        stages: COMPUTE,
    },
    Variable {
        name: "gl_GlobalInvocationID",
        ty: BuiltinType::UVec(3),
        stages: COMPUTE,
    },
    Variable {
        name: "gl_LocalInvocationIndex",
        ty: BuiltinType::UInt,
        stages: COMPUTE,
    },
    Variable {
        name: "gl_WorkGroupSize",
        ty: BuiltinType::UVec(3),
        stages: COMPUTE,
    },
    Variable {
        name: "gl_MaxPatchVertices",
        ty: BuiltinType::Int,
        stages: TESSELLATION,
    },
    Variable {
        name: "gl_TessLevelOuter",
        ty: BuiltinType::Vec(4),
        stages: TESSELLATION,
    },
    Variable {
        name: "gl_TessLevelInner",
        ty: BuiltinType::Vec(2),
        stages: TESSELLATION,
    },
];

pub fn matrix_variables() -> Vec<(&'static str, Type)> {
    [
        "gl_ModelViewMatrix",
        "gl_ProjectionMatrix",
        "gl_ModelViewProjectionMatrix",
        "gl_ModelViewMatrixInverse",
        "gl_ProjectionMatrixInverse",
        "gl_ModelViewProjectionMatrixInverse",
        "gl_TextureMatrix",
        "gl_TextureMatrixInverse",
    ]
    .iter()
    .map(|name| {
        (
            *name,
            if name.contains("Texture") {
                Type::Array {
                    element: Box::new(type_matrix()),
                    size: Some(8),
                }
            } else {
                type_matrix()
            },
        )
    })
    .collect()
}
fn type_matrix() -> Type {
    Type::Matrix {
        scalar: Scalar::Float,
        columns: 4,
        rows: 4,
    }
}
fn fog_type() -> Type {
    Type::Struct {
        name: String::from("gl_FogParameters"),
        fields: vec![
            Field {
                name: String::from("color"),
                ty: Type::Vector {
                    scalar: Scalar::Float,
                    size: 4,
                },
            },
            Field {
                name: String::from("density"),
                ty: Type::Float,
            },
            Field {
                name: String::from("start"),
                ty: Type::Float,
            },
            Field {
                name: String::from("end"),
                ty: Type::Float,
            },
            Field {
                name: String::from("scale"),
                ty: Type::Float,
            },
        ],
    }
}

pub fn all_graphics() -> u8 {
    ALL_GRAPHICS
}
pub fn all_stages() -> u8 {
    ALL
}

#[cfg(test)]
mod tests {
    use super::*;

    fn has(stage: Stage, name: &str, parameters: &[Type], return_type: &Type) -> bool {
        functions(stage).iter().any(|function| {
            function.name == name
                && function.parameters == parameters
                && function.return_type == *return_type
        })
    }

    #[test]
    fn exposes_numeric_and_matrix_overloads() {
        assert!(has(
            Stage::Fragment,
            "normalize",
            &[Type::Vector {
                scalar: Scalar::Float,
                size: 3,
            }],
            &Type::Vector {
                scalar: Scalar::Float,
                size: 3,
            },
        ));
        assert!(has(
            Stage::Vertex,
            "transpose",
            &[Type::Matrix {
                scalar: Scalar::Float,
                columns: 4,
                rows: 4,
            }],
            &Type::Matrix {
                scalar: Scalar::Float,
                columns: 4,
                rows: 4,
            },
        ));
        assert!(has(
            Stage::Fragment,
            "floatBitsToUint",
            &[Type::Float],
            &Type::UInt,
        ));
    }

    #[test]
    fn restricts_stage_builtins() {
        assert!(
            functions(Stage::Vertex)
                .iter()
                .all(|function| function.name != "barrier")
        );
        assert!(
            functions(Stage::Compute)
                .iter()
                .all(|function| function.name != "ftransform")
        );
        assert!(
            functions(Stage::Geometry)
                .iter()
                .any(|function| function.name == "emitVertex")
        );
    }
}
