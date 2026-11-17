use std::error::Error;
use std::fs;
use std::path::Path;

use glslc::geometry::mesh::PrimitiveTopology;
use glslc::{MeshCompileContext, Options, Source, compile_mesh};

#[test]
fn lowers_geometry_fixture_programs_through_mesh_contract() -> Result<(), Box<dyn Error>> {
    for (program, output, vertices, primitives) in [
        ("gbuffers_basic", "triangle_strip", 6, 2),
        ("gbuffers_line", "line_strip", 4, 3),
        ("gbuffers_textured", "points", 2, 2),
    ] {
        let sources = read_sources(program)?;
        let compiled = compile_mesh(
            &[Source::new(format!("{program}.vsh"), sources.vertex)],
            &[Source::new(format!("{program}.gsh"), sources.geometry)],
            &[Source::new(format!("{program}.fsh"), sources.fragment)],
            &[],
            &Options::default(),
            MeshCompileContext {
                pack: "geometry-fixtures",
                dimension: "",
                program,
            },
        )?;
        let metadata = compiled
            .reflection
            .mesh_metadata()
            .ok_or("mesh metadata missing")?;
        let expected_topology = match output {
            "triangle_strip" => PrimitiveTopology::TriangleStrip,
            "line_strip" => PrimitiveTopology::LineStrip,
            "points" => PrimitiveTopology::Points,
            _ => return Err("unknown fixture topology".into()),
        };
        assert_eq!(metadata.output_topology, expected_topology);
        assert_eq!(metadata.output_vertices, vertices);
        assert_eq!(metadata.output_primitives, primitives);
        assert!(metadata.names.object.starts_with("mtlr_object_"));
        assert!(metadata.names.mesh.starts_with("mtlr_mesh_"));
        assert!(compiled.msl.contains("[[mesh]]"));
    }
    Ok(())
}

fn read_sources(program: &str) -> Result<Sources, Box<dyn Error>> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../mod/run/shaderpacks/geometry-fixtures/shaders");
    Ok(Sources {
        vertex: fs::read_to_string(root.join(format!("{program}.vsh")))?,
        geometry: fs::read_to_string(root.join(format!("{program}.gsh")))?,
        fragment: fs::read_to_string(root.join(format!("{program}.fsh")))?,
    })
}

struct Sources {
    vertex: String,
    geometry: String,
    fragment: String,
}
