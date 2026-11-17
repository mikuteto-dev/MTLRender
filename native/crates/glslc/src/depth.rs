const VERTEX_ENTRY: &str = "vertex MtlrVertexOut main_vertex(";
const FRAGMENT_ENTRY: &str = "fragment MtlrFragmentOut main_fragment(";
const VERTEX_ASSIGNMENT: &str = "    mtlr_out.mtlr_position = mtlr_position;\n";
const VERTEX_FIXUP: &str = "    mtlr_position.z = (mtlr_position.w - mtlr_position.z) / 2.0;\n";
const FRAGMENT_ASSIGNMENT: &str = "    mtlr_out.mtlr_frag_depth = mtlr_frag_depth;\n";
const FRAGMENT_FIXUP: &str = "    mtlr_out.mtlr_frag_depth = 1.0 - mtlr_frag_depth;\n";

pub(crate) fn fix_pack_vertex_depth(msl: &str) -> String {
    let Some((body_start, body_end)) = entry_body(msl, VERTEX_ENTRY) else {
        return String::from(msl);
    };
    let body = &msl[body_start..body_end];
    if body.contains(VERTEX_FIXUP) {
        return String::from(msl);
    }
    if let Some(offset) = body.find(VERTEX_ASSIGNMENT) {
        return insert(msl, body_start + offset, VERTEX_FIXUP);
    }
    if let Some(offset) = body.find("    return mtlr_out;\n") {
        return insert(msl, body_start + offset, VERTEX_FIXUP);
    }
    fix_struct_return(msl, body_start, body_end, "MtlrVertexOut", "mtlr_position")
}

pub(crate) fn fix_pack_fragment_depth(msl: &str) -> String {
    let Some((body_start, body_end)) = entry_body(msl, FRAGMENT_ENTRY) else {
        return String::from(msl);
    };
    let Some(entry_start) = msl[..body_start].rfind(FRAGMENT_ENTRY) else {
        return String::from(msl);
    };
    if !msl[..entry_start].contains("float mtlr_frag_depth [[depth(") {
        return String::from(msl);
    }
    let body = &msl[body_start..body_end];
    if body.contains(FRAGMENT_FIXUP) {
        return String::from(msl);
    }
    if let Some(offset) = body.find(FRAGMENT_ASSIGNMENT) {
        let mut output = String::with_capacity(msl.len() + 4);
        output.push_str(&msl[..body_start + offset]);
        output.push_str(FRAGMENT_FIXUP);
        output.push_str(&msl[body_start + offset + FRAGMENT_ASSIGNMENT.len()..]);
        return output;
    }
    if let Some(offset) = body.find("    return mtlr_out;\n") {
        let fixup = "    mtlr_out.mtlr_frag_depth = 1.0 - mtlr_out.mtlr_frag_depth;\n";
        return insert(msl, body_start + offset, fixup);
    }
    fix_struct_return(
        msl,
        body_start,
        body_end,
        "MtlrFragmentOut",
        "mtlr_frag_depth",
    )
}

fn fix_struct_return(
    msl: &str,
    body_start: usize,
    body_end: usize,
    output_type: &str,
    field: &str,
) -> String {
    let body = &msl[body_start..body_end];
    let marker = format!("return {output_type}{{");
    let Some(relative_start) = body.find(&marker) else {
        return String::from(msl);
    };
    let start = body_start + relative_start;
    let Some(relative_end) = msl[start..body_end].find(';') else {
        return String::from(msl);
    };
    let end = start + relative_end + 1;
    let expression = &msl[start + "return ".len()..end - 1];
    let mut output = String::with_capacity(msl.len() + 96);
    output.push_str(&msl[..start]);
    if field == "mtlr_position" {
        output.push_str(&format!(
            "{output_type} mtlr_pack_return = {expression};\n    mtlr_pack_return.mtlr_position.z = (mtlr_pack_return.mtlr_position.w - mtlr_pack_return.mtlr_position.z) / 2.0;\n    return mtlr_pack_return;"
        ));
    } else {
        output.push_str(&format!(
            "{output_type} mtlr_pack_return = {expression};\n    mtlr_pack_return.mtlr_frag_depth = 1.0 - mtlr_pack_return.mtlr_frag_depth;\n    return mtlr_pack_return;"
        ));
    }
    output.push_str(&msl[end..]);
    output
}

fn entry_body(msl: &str, marker: &str) -> Option<(usize, usize)> {
    let entry = msl.find(marker)?;
    let open = entry + msl[entry..].find('{')?;
    let mut level = 0_u32;
    for (offset, character) in msl[open..].char_indices() {
        match character {
            '{' => level = level.saturating_add(1),
            '}' => {
                level = level.checked_sub(1)?;
                if level == 0 {
                    return Some((open + 1, open + offset));
                }
            }
            _ => {}
        }
    }
    None
}

fn insert(msl: &str, offset: usize, text: &str) -> String {
    let mut output = String::with_capacity(msl.len() + text.len());
    output.push_str(&msl[..offset]);
    output.push_str(text);
    output.push_str(&msl[offset..]);
    output
}

#[cfg(test)]
mod tests {
    use super::{fix_pack_fragment_depth, fix_pack_vertex_depth};

    #[test]
    fn vertex_fixup_changes_only_homogeneous_z_in_the_pack_entry() {
        let source = "struct MtlrVertexOut { float4 mtlr_position [[position]]; };\nvertex MtlrVertexOut main_vertex() {\n    float4 mtlr_position = float4(2.0, 3.0, -1.0, 4.0);\n    mtlr_out.mtlr_position = mtlr_position;\n    return mtlr_out;\n}\n";
        let fixed = fix_pack_vertex_depth(source);
        assert_eq!(fixed.matches("mtlr_position.z =").count(), 1);
        assert!(fixed.contains("(mtlr_position.w - mtlr_position.z) / 2.0"));
        assert!(!fixed.contains("mtlr_position.x ="));
        assert!(!fixed.contains("mtlr_position.y ="));
        assert!(!fixed.contains("mtlr_position.w ="));
    }

    #[test]
    fn fragment_fixup_requires_the_reflected_depth_field_and_preserves_color() {
        let source = "struct MtlrFragmentOut { float4 color [[color(0)]]; float mtlr_frag_depth [[depth(any)]]; };\nfragment MtlrFragmentOut main_fragment() {\n    if (true) { mtlr_frag_depth = 0.25; }\n    mtlr_out.color = color;\n    mtlr_out.mtlr_frag_depth = mtlr_frag_depth;\n    return mtlr_out;\n}\n";
        let fixed = fix_pack_fragment_depth(source);
        assert_eq!(fixed.matches("1.0 - mtlr_frag_depth").count(), 1);
        assert!(fixed.contains("mtlr_out.color = color;"));
        assert_eq!(
            fix_pack_fragment_depth(
                "fragment MtlrFragmentOut main_fragment() { return mtlr_out; }"
            ),
            "fragment MtlrFragmentOut main_fragment() { return mtlr_out; }"
        );
    }

    #[test]
    fn struct_return_forms_are_fixed_inside_the_entry_only() {
        let source = "struct MtlrFragmentOut { float4 color [[color(0)]]; float mtlr_frag_depth [[depth(any)]]; };\nfragment MtlrFragmentOut main_fragment() { return MtlrFragmentOut{color, depth}; }\n";
        let fixed = fix_pack_fragment_depth(source);
        assert!(fixed.contains(
            "mtlr_pack_return.mtlr_frag_depth = 1.0 - mtlr_pack_return.mtlr_frag_depth;"
        ));
        assert_eq!(fixed.matches("1.0 -").count(), 1);
    }
}
