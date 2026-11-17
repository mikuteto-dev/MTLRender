pub mod lower;
pub mod metadata;
pub mod msl;

pub use metadata::{Metadata, Spacing, TessellationDescriptor, Topology};

use crate::{CompileErrors, Stage, parse::Ast};

pub fn analyze(ast: &Ast, stage: Stage) -> Result<Option<Metadata>, CompileErrors> {
    metadata::analyze(ast, stage)
}
