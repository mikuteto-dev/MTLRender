use crate::transform::{TessellationDomain, TessellationPlan};
use crate::{CompileError, CompileErrors, ErrorKind, Location, Stage};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StageForm {
    ControlKernel,
    EvaluationVertex,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct LoweredStage {
    pub form: StageForm,
    pub domain: TessellationDomain,
    pub patch_vertices: u32,
    pub workgroup_size: [u32; 3],
}

pub fn lower(stage: Stage, plan: &TessellationPlan) -> Result<LoweredStage, CompileErrors> {
    let form = match stage {
        Stage::TessellationControl => StageForm::ControlKernel,
        Stage::TessellationEvaluation => StageForm::EvaluationVertex,
        _ => {
            return Err(CompileErrors::one(CompileError::new(
                ErrorKind::Transform,
                "tessellation lowering requires a tessellation stage",
                Some(Location::new(
                    &plan.location.file,
                    plan.location.line,
                    plan.location.column,
                )),
            )));
        }
    };
    if plan.control.patch.input_count != plan.control.patch.output_count {
        return Err(CompileErrors::one(CompileError::new(
            ErrorKind::Transform,
            "tessellation patch input and output records must have the same count",
            Some(Location::new(
                &plan.location.file,
                plan.location.line,
                plan.location.column,
            )),
        )));
    }
    if plan.control.patch.input_count == 0 || plan.control.patch.input_count > 32 {
        return Err(CompileErrors::one(CompileError::unsupported_at(
            "tessellation patch record exceeds the generated MSL contract",
            Some(Location::new(
                &plan.location.file,
                plan.location.line,
                plan.location.column,
            )),
        )));
    }
    Ok(LoweredStage {
        form,
        domain: plan.contract.domain,
        patch_vertices: plan.control.patch.input_count,
        workgroup_size: plan.workgroup_size,
    })
}

#[cfg(test)]
mod tests {
    use crate::{Options, Source, Stage, compile};

    #[test]
    fn lowers_all_domains_and_spacing_metadata_to_stage_forms() {
        for (layout, entry) in [
            (
                "triangles, equal_spacing",
                "mtlr_tessellation_evaluation_triangles",
            ),
            (
                "quads, fractional_even_spacing",
                "mtlr_tessellation_evaluation_quads",
            ),
            (
                "isolines, fractional_odd_spacing",
                "mtlr_tessellation_evaluation_isolines",
            ),
        ] {
            let source = Source::new(
                "test.tes",
                format!("layout({layout}) in; void main() {{ gl_TessCoord; gl_PrimitiveID; }}"),
            );
            let result = compile(
                Stage::TessellationEvaluation,
                &[source],
                &[],
                &Options::default(),
            );
            assert!(result.is_ok(), "{result:?}");
            let Ok(compiled) = result else { continue };
            assert!(compiled.msl.contains(entry));
            assert!(compiled.msl.contains("MtlrTessVertex"));
            assert!(compiled.msl.contains("vertex_id"));
            assert!(compiled.msl.contains("gl_TessCoord"));
            assert!(compiled.msl.contains("gl_PrimitiveID"));
            assert!(
                compiled
                    .reflection
                    .resources
                    .iter()
                    .any(|resource| resource.name == "tessellated_vertices")
            );
        }
    }

    #[test]
    fn generated_tessellation_forms_pass_metal_compiler_when_available() {
        let sources = [
            (
                Stage::TessellationControl,
                "metal-check.tcs",
                "layout(vertices = 3) out; void main() {}",
            ),
            (
                Stage::TessellationEvaluation,
                "metal-check.tes",
                "layout(triangles, equal_spacing) in; void main() {}",
            ),
        ];
        for (stage, name, text) in sources {
            let result = compile(stage, &[Source::new(name, text)], &[], &Options::default());
            assert!(result.is_ok(), "{result:?}");
            let Ok(compiled) = result else { continue };
            let path = std::env::temp_dir().join(name).with_extension("metal");
            let output = path.with_extension("air");
            let written = std::fs::write(&path, compiled.msl.as_bytes());
            assert!(written.is_ok(), "{written:?}");
            let status = std::process::Command::new("xcrun")
                .args(["-sdk", "macosx", "metal", "-std=metal3.2", "-c"])
                .arg(&path)
                .arg("-o")
                .arg(&output)
                .status();
            let _ = std::fs::remove_file(&path);
            let _ = std::fs::remove_file(&output);
            if let Ok(status) = status {
                assert!(status.success(), "{status:?}");
            }
        }
    }

    #[test]
    fn lowers_control_invocations_with_explicit_patch_barrier_and_factor_record() {
        let source = Source::new(
            "test.tcs",
            "layout(vertices = 3) out; void main() { gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position; gl_TessLevelOuter[0] = 2.0; }",
        );
        let result = compile(
            Stage::TessellationControl,
            &[source],
            &[],
            &Options::default(),
        );
        assert!(result.is_ok(), "{result:?}");
        let Ok(compiled) = result else { return };
        assert!(
            compiled
                .msl
                .contains("kernel void mtlr_tessellation_control")
        );
        assert!(compiled.msl.contains("patch_inputs"));
        assert!(compiled.msl.contains("patch_outputs"));
        assert!(compiled.msl.contains("patch_factors"));
        assert!(compiled.msl.contains("thread_position_in_grid"));
        assert!(compiled.msl.contains("threadgroup_barrier"));
        assert!(compiled.msl.contains("outer[0]"));
        assert!(
            compiled
                .reflection
                .resources
                .iter()
                .any(|resource| resource.name == "tess_patch_factors")
        );
    }
}
