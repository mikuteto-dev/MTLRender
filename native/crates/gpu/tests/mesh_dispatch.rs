use gpu::{
    Command, Decoder, MESH_DISPATCH_OPCODE, MESH_DISPATCH_WORDS, MESH_MAX_PAYLOAD_BYTES,
    MESH_MAX_PRIMITIVES, MESH_MAX_THREADGROUP_DIMENSION, MESH_MAX_VERTICES, MeshDispatchRequest,
    valid_mesh_dispatch,
};

fn base_request() -> MeshDispatchRequest {
    MeshDispatchRequest {
        object_threadgroups: [2, 3, 4],
        mesh_threadgroups: [5, 6, 7],
        payload_bytes: 256,
        vertex_count: 12,
        primitive_count: 34,
        resource_address: 0x1122_3344_5566_7788,
        resource_offset: 8,
        resource_length: 4096,
        payload_offset: 16,
        vertex_offset: 128,
        vertex_bytes: 256,
        primitive_offset: 512,
        primitive_bytes: 1024,
        dispatch_geometry: [9, 10, 11],
    }
}

fn u64_words(words: &mut Vec<u32>, value: u64) {
    words.extend([value as u32, (value >> 32) as u32]);
}

fn words(request: &MeshDispatchRequest) -> Vec<u32> {
    let mut words = vec![(MESH_DISPATCH_WORDS as u32) << 16 | MESH_DISPATCH_OPCODE];
    words.extend(request.object_threadgroups);
    words.extend(request.mesh_threadgroups);
    words.extend([
        request.payload_bytes,
        request.vertex_count,
        request.primitive_count,
    ]);
    for value in [
        request.resource_address,
        request.resource_offset,
        request.resource_length,
        request.payload_offset,
        request.vertex_offset,
        request.vertex_bytes,
        request.primitive_offset,
        request.primitive_bytes,
    ] {
        u64_words(&mut words, value);
    }
    words.extend(request.dispatch_geometry);
    words.push(0);
    words
}

#[test]
fn decoder_matches_java_golden_fields() {
    let request = base_request();
    let golden = [
        0x001e001c, 2, 3, 4, 5, 6, 7, 256, 12, 34, 0x55667788, 0x11223344, 8, 0, 4096, 0, 16, 0,
        128, 0, 256, 0, 512, 0, 1024, 0, 9, 10, 11, 0,
    ];
    assert_eq!(words(&request), golden);
    let command = Decoder::new(&golden).next();
    assert!(matches!(command, Some(Command::MeshDispatch(decoded))
        if decoded.object_threadgroups == [2, 3, 4]
            && decoded.mesh_threadgroups == [5, 6, 7]
            && decoded.payload_bytes == 256
            && decoded.vertex_count == 12
            && decoded.primitive_count == 34
            && decoded.resource_address == 0x1122_3344_5566_7788
            && decoded.resource_offset == 8
            && decoded.resource_length == 4096
            && decoded.payload_offset == 16
            && decoded.vertex_offset == 128
            && decoded.vertex_bytes == 256
            && decoded.primitive_offset == 512
            && decoded.primitive_bytes == 1024
            && decoded.dispatch_geometry == [9, 10, 11]));
}

#[test]
fn validation_accepts_minimum_and_boundaries() {
    let mut request = base_request();
    request.object_threadgroups = [1, 1, 1];
    request.mesh_threadgroups = [1, 1, 1];
    request.dispatch_geometry = [1, 1, 1];
    request.payload_bytes = 1;
    request.vertex_count = 1;
    request.primitive_count = 1;
    assert!(valid_mesh_dispatch(&request));

    request.resource_length = u64::from(MESH_MAX_PAYLOAD_BYTES);
    request.payload_offset = 0;
    request.payload_bytes = MESH_MAX_PAYLOAD_BYTES;
    request.vertex_count = MESH_MAX_VERTICES;
    request.primitive_count = MESH_MAX_PRIMITIVES;
    request.object_threadgroups = [1024, 1, 1];
    request.dispatch_geometry = [MESH_MAX_THREADGROUP_DIMENSION, 1, 1];
    assert!(valid_mesh_dispatch(&request));
}

#[test]
fn validation_rejects_invalid_values_and_ranges() {
    let mut request = base_request();
    request.payload_bytes = 0;
    assert!(!valid_mesh_dispatch(&request));
    request = base_request();
    request.payload_bytes = MESH_MAX_PAYLOAD_BYTES + 1;
    assert!(!valid_mesh_dispatch(&request));
    request = base_request();
    request.vertex_count = MESH_MAX_VERTICES + 1;
    assert!(!valid_mesh_dispatch(&request));
    request = base_request();
    request.primitive_count = MESH_MAX_PRIMITIVES + 1;
    assert!(!valid_mesh_dispatch(&request));
    request = base_request();
    request.resource_address = 0;
    assert!(!valid_mesh_dispatch(&request));
    request = base_request();
    request.payload_offset = request.resource_length;
    assert!(!valid_mesh_dispatch(&request));
    request = base_request();
    request.resource_address = u64::MAX;
    request.resource_offset = 1;
    assert!(!valid_mesh_dispatch(&request));
}

#[test]
fn truncated_payload_is_rejected_without_partial_command() {
    let full = words(&base_request());
    for length in 0..full.len() {
        if let Some(prefix) = full.get(..length) {
            assert!(Decoder::new(prefix).next().is_none());
        }
    }
    let mut malformed = full;
    if let Some(header) = malformed.first_mut() {
        *header = ((MESH_DISPATCH_WORDS as u32 - 2) << 16) | MESH_DISPATCH_OPCODE;
    }
    assert!(Decoder::new(&malformed).next().is_none());
}

#[test]
fn following_draw_and_copy_commands_resynchronize() {
    let mut stream = words(&base_request());
    stream.extend([9, 6, 1, 0, 0]);
    stream.extend([13, 0x2000, 0, 4, 0x3000, 0, 8, 16]);
    let mut decoder = Decoder::new(&stream);
    assert!(matches!(decoder.next(), Some(Command::MeshDispatch(_))));
    assert!(matches!(
        decoder.next(),
        Some(Command::Draw {
            count: 6,
            instances: 1,
            ..
        })
    ));
    assert!(matches!(
        decoder.next(),
        Some(Command::CopyBuffer {
            src: 0x2000,
            dst: 0x3000,
            size: 16,
            ..
        })
    ));
    assert!(decoder.next().is_none());
}
