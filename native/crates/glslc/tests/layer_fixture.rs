use std::error::Error;
use std::fs;
use std::path::Path;

use glslc::{Options, Source, Stage, compile};

#[test]
fn layered_fixture_produces_render_metadata_without_geometry_stage() -> Result<(), Box<dyn Error>> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../mod/run/shaderpacks/geometry-fixtures/shaders");
    let geometry = fs::read_to_string(root.join("gbuffers_layered.gsh"))?;
    let vertex = fs::read_to_string(root.join("gbuffers_layered.vsh"))?;
    let geometry_result = compile(
        Stage::Geometry,
        &[Source::new("gbuffers_layered.gsh", geometry)],
        &[],
        &Options::default(),
    )?;
    let metadata = geometry_result
        .reflection
        .geometry
        .ok_or("layered geometry reflection is missing")?;
    assert_eq!(metadata.kind, "Layered");
    assert_eq!(metadata.instance_count, 3);
    assert_eq!(metadata.layer_mapping, vec![1, 2, 3]);
    assert!(geometry_result.msl.is_empty());

    let vertex_result = compile(
        Stage::Vertex,
        &[Source::new("gbuffers_layered.vsh", vertex)],
        &[],
        &Options::default(),
    )?;
    assert!(vertex_result.msl.contains("instance_id"));
    Ok(())
}
