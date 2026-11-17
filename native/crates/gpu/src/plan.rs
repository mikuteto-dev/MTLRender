use std::collections::HashMap;

use crate::command::{ColorTarget, Command, DepthTarget};
use mtl4::MAX_COLOR_ATTACHMENTS;
use objc2_metal::MTLClearColor;

pub const DEPTH: u16 = 1 << MAX_COLOR_ATTACHMENTS;

#[derive(Clone, Copy, Default)]
pub struct Step {
    pub merge: bool,
    pub discard: u16,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Next {
    Read,
    Overwrite(usize),
}

type Targets<'c> = (&'c [ColorTarget], &'c DepthTarget);

pub fn color_bit(index: usize) -> u16 {
    if index < MAX_COLOR_ATTACHMENTS {
        1 << index
    } else {
        0
    }
}

pub fn plan(commands: &mut [Command], target: impl Fn(u64) -> Option<(usize, usize)>) -> Vec<Step> {
    let mut steps = vec![Step::default(); commands.len()];
    fold_clears(commands, &target, &mut steps);
    mark_merges(commands, &target, &mut steps);
    mark_discards(commands, &target, &mut steps);
    steps
}

type Pending = HashMap<(usize, usize), (usize, MTLClearColor, f32)>;

fn cancel_root(pending: &mut Pending, target: &impl Fn(u64) -> Option<(usize, usize)>, view: u64) {
    if let Some((root, _)) = target(view) {
        pending.retain(|&(pending_root, _), _| pending_root != root);
    }
}

fn fold_clears(
    commands: &mut [Command],
    target: &impl Fn(u64) -> Option<(usize, usize)>,
    steps: &mut [Step],
) {
    let mut pending: Pending = HashMap::new();
    for index in 0..commands.len() {
        let Some(command) = commands.get_mut(index) else {
            continue;
        };
        match command {
            Command::Clear {
                texture,
                color,
                depth,
            } => {
                if let Some(key) = target(*texture) {
                    pending.insert(key, (index, *color, *depth));
                }
            }
            Command::BindTexture { view, .. } | Command::BlitToSurface(view) => {
                cancel_root(&mut pending, target, *view);
            }
            Command::ClearRegion { color, depth, .. } => {
                cancel_root(&mut pending, target, *color);
                cancel_root(&mut pending, target, *depth);
            }
            Command::CopyBufferToTexture { dst, .. } => {
                cancel_root(&mut pending, target, dst.texture);
            }
            Command::CopyTextureToBuffer { src, .. } => {
                cancel_root(&mut pending, target, src.texture);
            }
            Command::CopyTextureToTexture { src, dst } => {
                cancel_root(&mut pending, target, src.texture);
                cancel_root(&mut pending, target, dst.texture);
            }
            Command::GenerateMipmaps(request) => {
                cancel_root(&mut pending, target, request.view);
            }
            Command::PackDepthConvert {
                source,
                destination,
            } => {
                cancel_root(&mut pending, target, *source);
                cancel_root(&mut pending, target, *destination);
            }
            Command::TerrainCull(request) if request.depth != 0 => {
                cancel_root(&mut pending, target, request.depth);
            }
            Command::FeatureCull(request) if request.depth != 0 => {
                cancel_root(&mut pending, target, request.depth);
            }
            Command::MetalFx(request) => {
                cancel_root(&mut pending, target, request.color);
                cancel_root(&mut pending, target, request.depth);
                cancel_root(&mut pending, target, request.output);
            }
            Command::BeginPass { colors, depth, .. } => {
                for color in colors.iter_mut() {
                    let Some(key) = target(color.view) else {
                        continue;
                    };
                    let Some(&(clear_index, value, _)) = pending.get(&key) else {
                        continue;
                    };
                    if color.clear.is_none() {
                        color.clear = Some(value);
                    }
                    if let Some(step) = steps.get_mut(clear_index) {
                        step.discard = 1;
                    }
                    pending.remove(&key);
                }
                if let Some(key) = target(depth.view)
                    && let Some(&(clear_index, _, value)) = pending.get(&key)
                {
                    if depth.clear.is_none() {
                        depth.clear = Some(value);
                    }
                    if let Some(step) = steps.get_mut(clear_index) {
                        step.discard = 1;
                    }
                    pending.remove(&key);
                }
                let attached: Vec<(usize, usize)> = attachments((colors.as_slice(), &*depth))
                    .filter_map(|(_, view, _)| target(view))
                    .collect();
                pending.retain(|&(root, level), _| {
                    !attached.iter().any(|&(attached_root, attached_level)| {
                        attached_root == root && attached_level != level
                    })
                });
            }
            _ => {}
        }
    }
}

fn attachments<'c>((colors, depth): Targets<'c>) -> impl Iterator<Item = (u16, u64, bool)> + 'c {
    colors
        .iter()
        .enumerate()
        .map(|(index, color)| (color_bit(index), color.view, color.clear.is_some()))
        .chain(std::iter::once((DEPTH, depth.view, depth.clear.is_some())))
}

fn same_views(a: Targets, b: Targets) -> bool {
    a.0.len() == b.0.len()
        && a.0.iter().zip(b.0).all(|(x, y)| x.view == y.view)
        && a.1.view == b.1.view
}

fn mark_merges(
    commands: &[Command],
    target: &impl Fn(u64) -> Option<(usize, usize)>,
    steps: &mut [Step],
) {
    let mut previous: Option<Targets> = None;
    let mut open: Option<(usize, Vec<usize>)> = None;
    let mut after_end = false;
    for (index, command) in commands.iter().enumerate() {
        match command {
            Command::BeginPass { colors, depth, .. } => {
                let targets = (colors.as_slice(), depth);
                let loads = attachments(targets).all(|(_, _, clear)| !clear);
                if let Some(step) = steps.get_mut(index) {
                    step.merge = after_end
                        && loads
                        && previous.is_some_and(|last| same_views(last, targets));
                }
                let roots = attachments(targets)
                    .filter_map(|(_, view, _)| target(view))
                    .map(|(root, _)| root)
                    .collect();
                open = Some((index, roots));
                previous = Some(targets);
            }
            Command::BindTexture { view, .. } => {
                if let (Some((begin, roots)), Some((root, _))) = (&open, target(*view))
                    && roots.contains(&root)
                    && let Some(step) = steps.get_mut(*begin)
                {
                    step.merge = false;
                }
            }
            Command::EndPass => open = None,
            Command::TerrainCull(_)
            | Command::FeatureCull(_)
            | Command::TerrainExpand(_)
            | Command::GenerateMipmaps(_)
            | Command::PackDepthConvert { .. }
            | Command::MetalFx(_) => {
                open = None;
                previous = None;
            }
            _ => {}
        }
        after_end = matches!(command, Command::EndPass);
    }
}

fn mark_discards(
    commands: &[Command],
    target: &impl Fn(u64) -> Option<(usize, usize)>,
    steps: &mut [Step],
) {
    let mut next: HashMap<usize, Next> = HashMap::new();
    let mut generated: HashMap<(usize, usize), Next> = HashMap::new();
    let overwritten =
        |next: &HashMap<usize, Next>, generated: &HashMap<(usize, usize), Next>, view: u64| {
            target(view).is_some_and(|(root, level)| {
                generated.get(&(root, level)) == Some(&Next::Overwrite(level))
                    || next.get(&root) == Some(&Next::Overwrite(level))
            })
        };
    let mut chain = 0;
    for (index, command) in commands.iter().enumerate().rev() {
        let mut access = |view: u64, overwrite: bool| {
            if let Some((root, level)) = target(view) {
                let state = if overwrite {
                    Next::Overwrite(level)
                } else {
                    Next::Read
                };
                next.insert(root, state);
            }
        };
        match command {
            Command::EndPass => {
                if !steps.get(index + 1).is_some_and(|step| step.merge) {
                    chain = commands
                        .get(..index)
                        .and_then(|before| {
                            before.iter().rev().find_map(|earlier| match earlier {
                                Command::BeginPass { colors, depth, .. } => {
                                    Some((colors.as_slice(), depth))
                                }
                                _ => None,
                            })
                        })
                        .map_or(0, |targets| {
                            attachments(targets)
                                .filter(|(_, view, _)| overwritten(&next, &generated, *view))
                                .fold(0, |mask, (bit, _, _)| mask | bit)
                        });
                }
            }
            Command::BeginPass { colors, depth, .. } => {
                if let Some(step) = steps.get_mut(index) {
                    step.discard = chain;
                }
                for (_, view, clear) in attachments((colors.as_slice(), depth)) {
                    if let Some((root, level)) = target(view) {
                        next.insert(
                            root,
                            if clear {
                                Next::Overwrite(level)
                            } else {
                                Next::Read
                            },
                        );
                    }
                }
            }
            Command::Clear { texture, .. } => {
                if overwritten(&next, &generated, *texture)
                    && let Some(step) = steps.get_mut(index)
                {
                    step.discard = 1;
                }
                if let Some((root, level)) = target(*texture) {
                    next.insert(root, Next::Overwrite(level));
                }
            }
            Command::BindTexture { view, .. } | Command::BlitToSurface(view) => {
                access(*view, false)
            }
            Command::ClearRegion { color, depth, .. } => {
                access(*color, false);
                access(*depth, false);
            }
            Command::CopyBufferToTexture { dst, .. } => access(dst.texture, false),
            Command::CopyTextureToBuffer { src, .. } => access(src.texture, false),
            Command::CopyTextureToTexture { src, dst } => {
                access(src.texture, false);
                access(dst.texture, false);
            }
            Command::GenerateMipmaps(request) => {
                access(request.view, false);
                if let Some((root, base_level)) = target(request.view) {
                    for level in 1..request.mip_levels {
                        generated.insert(
                            (root, base_level + request.base_mip + level),
                            Next::Overwrite(base_level + request.base_mip + level),
                        );
                    }
                }
            }
            Command::PackDepthConvert {
                source,
                destination,
            } => {
                access(*source, false);
                access(*destination, true);
            }
            Command::TerrainCull(request) if request.depth != 0 => access(request.depth, false),
            Command::FeatureCull(request) if request.depth != 0 => access(request.depth, false),
            Command::MetalFx(request) => {
                access(request.output, true);
                access(request.color, false);
                access(request.depth, false);
                if request.mode == 3 {
                    access(request.previous_color, false);
                    access(request.motion, false);
                    access(request.ui, false);
                }
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use objc2_metal::MTLClearColor;

    use super::{DEPTH, Step, color_bit, plan};
    use crate::command::{
        ColorTarget, Command, CullRequest, DepthTarget, ExpandRequest, FeatureCullRequest,
        GenerateMipmapsRequest, MetalFxRequest,
    };

    const BLACK: MTLClearColor = MTLClearColor {
        red: 0.0,
        green: 0.0,
        blue: 0.0,
        alpha: 0.0,
    };

    fn lookup(view: u64) -> Option<(usize, usize)> {
        (view != 0).then_some(((view / 10) as usize, (view % 10) as usize))
    }

    fn pass(color: (u64, bool), depth: (u64, bool)) -> Command {
        Command::BeginPass {
            id: 0,
            colors: vec![ColorTarget {
                view: color.0,
                clear: color.1.then_some(BLACK),
            }],
            depth: DepthTarget {
                view: depth.0,
                clear: depth.1.then_some(0.0),
            },
            area: [0, 0, 1, 1],
        }
    }

    fn clear(texture: u64) -> Command {
        Command::Clear {
            texture,
            color: BLACK,
            depth: 0.0,
        }
    }

    fn cull(depth: u64) -> Command {
        Command::TerrainCull(CullRequest {
            phase: 0,
            mode: 1,
            units: 0,
            units_offset: 0,
            count: 1,
            first: [0, 1, 2],
            indices: 0,
            sections: 1,
            out: 0,
            out_offset: 0,
            args_offset: 0,
            depth,
            clip: [0.0; 16],
            previous_clip: [0.0; 16],
            camera_block: [0; 3],
            camera_offset: [0.0; 3],
            from_args: 0,
            count_args_offset: 0,
        })
    }

    fn feature(depth: u64) -> Command {
        Command::FeatureCull(FeatureCullRequest {
            aabbs: 1,
            aabbs_offset: 0,
            count: 1,
            stride_words: 6,
            out: 2,
            out_offset: 0,
            depth,
            clip: [1.0; 16],
            previous_clip: [1.0; 16],
        })
    }

    fn metalfx(input: u64, depth: u64, output: u64) -> Command {
        Command::MetalFx(MetalFxRequest {
            mode: 1,
            color: input,
            current_color: input,
            previous_color: 0,
            depth,
            motion: 0,
            ui: 0,
            output,
            input: [1280, 720],
            output_size: [2560, 1440],
            delta_time: 0.0,
            motion_scale: [0.0; 2],
            camera: [0.0; 4],
            world_to_view: [0.0; 16],
            view_to_clip: [0.0; 16],
            jitter: [0.0; 2],
            current_inverse: [0.0; 16],
            previous_clip: [0.0; 16],
            reset: false,
            ui_composited: false,
            depth_reversed: true,
        })
    }

    fn expand() -> Command {
        Command::TerrainExpand(ExpandRequest {
            updates: 0,
            updates_offset: 0,
            update_count: 0,
            visible: 0,
            visible_offset: 0,
            visible_count: 1,
            sections: 1,
            cull_mask: 0,
            layer_mask: 7,
            options: 0,
            indices: 0,
            capacity: 1,
            camera_block: [0; 3],
            camera_offset: [0.0; 3],
            out: 0,
            out_offset: 0,
            args_offset: 0,
        })
    }

    fn sample(view: u64) -> Command {
        Command::BindTexture {
            texture_index: 0,
            sampler_index: 0,
            view,
            sampler: 0,
            stages: 2,
        }
    }

    fn mipmaps(view: u64) -> Command {
        Command::GenerateMipmaps(GenerateMipmapsRequest {
            view,
            base_mip: 0,
            mip_levels: 4,
        })
    }

    fn steps(commands: &mut [Command]) -> Vec<(bool, u16)> {
        plan(commands, lookup)
            .into_iter()
            .map(|Step { merge, discard }| (merge, discard))
            .collect()
    }

    #[test]
    fn merges_consecutive_loads_on_same_views() {
        let mut commands = [
            pass((10, true), (20, true)),
            Command::EndPass,
            pass((10, false), (20, false)),
            Command::EndPass,
        ];
        let merges: Vec<bool> = steps(&mut commands).iter().map(|s| s.0).collect();
        assert_eq!(merges, [false, false, true, false]);
    }

    #[test]
    fn does_not_merge_clear_other_views_gap_or_self_sampling() {
        let mut commands = [
            pass((10, false), (20, false)),
            Command::EndPass,
            pass((10, true), (20, false)),
            Command::EndPass,
            pass((10, false), (0, false)),
            Command::EndPass,
            clear(30),
            pass((10, false), (0, false)),
            Command::EndPass,
            pass((10, false), (0, false)),
            sample(11),
            Command::EndPass,
        ];
        assert!(steps(&mut commands).iter().all(|s| !s.0));
    }

    #[test]
    fn discards_depth_overwritten_by_later_clear() {
        let mut commands = [
            pass((10, true), (20, true)),
            Command::EndPass,
            pass((10, false), (20, false)),
            Command::EndPass,
            clear(20),
            pass((10, false), (20, false)),
            Command::EndPass,
        ];
        let discards: Vec<u16> = steps(&mut commands).iter().map(|s| s.1).collect();
        assert_eq!(discards, [DEPTH, 0, DEPTH, 0, 1, 0, 0]);
    }

    #[test]
    fn keeps_attachments_read_before_overwrite() {
        let mut commands = [
            pass((10, false), (20, false)),
            Command::EndPass,
            pass((30, false), (0, false)),
            sample(20),
            sample(10),
            Command::EndPass,
            clear(20),
            clear(10),
            clear(31),
        ];
        let discards: Vec<u16> = steps(&mut commands).iter().map(|s| s.1).collect();
        assert_eq!(discards, [0, 0, 0, 0, 0, 0, 0, 0, 0]);
    }

    #[test]
    fn discards_color_cleared_by_next_pass_and_skips_dead_clears() {
        let mut commands = [
            clear(10),
            clear(10),
            pass((10, false), (0, false)),
            Command::EndPass,
            pass((10, true), (0, false)),
            Command::EndPass,
            Command::BlitToSurface(10),
        ];
        let discards: Vec<u16> = steps(&mut commands).iter().map(|s| s.1).collect();
        assert_eq!(discards, [1, 1, color_bit(0), 0, 0, 0, 0]);
    }

    #[test]
    fn folds_full_clear_into_color_load() {
        let mut commands = [clear(11), pass((11, false), (0, false))];
        let planned = plan(&mut commands, lookup);
        assert_eq!(planned.first().map(|step| step.discard), Some(1));
        assert!(matches!(
            commands.get(1),
            Some(Command::BeginPass { colors, .. })
                if colors.first().is_some_and(|color| color.clear.is_some())
        ));
    }

    #[test]
    fn does_not_fold_clear_read_before_pass() {
        let mut commands = [clear(11), sample(11), pass((11, false), (0, false))];
        let planned = plan(&mut commands, lookup);
        assert_eq!(planned.first().map(|step| step.discard), Some(0));
        assert!(matches!(
            commands.get(2),
            Some(Command::BeginPass { colors, .. })
                if colors.first().is_some_and(|color| color.clear.is_none())
        ));
    }

    #[test]
    fn folds_full_clear_into_depth_load() {
        let mut commands = [clear(21), pass((11, false), (21, false))];
        let planned = plan(&mut commands, lookup);
        assert_eq!(planned.first().map(|step| step.discard), Some(1));
        assert!(matches!(
            commands.get(1),
            Some(Command::BeginPass { depth, .. }) if depth.clear.is_some()
        ));
    }

    #[test]
    fn mipmaps_break_merge_between_passes() {
        let mut commands = [
            pass((10, true), (20, true)),
            Command::EndPass,
            mipmaps(10),
            pass((10, false), (20, false)),
            Command::EndPass,
        ];
        assert_eq!(steps(&mut commands).get(3).map(|step| step.0), Some(false));
    }

    #[test]
    fn mipmaps_cancel_pending_clear_for_base_read() {
        let mut commands = [clear(10), mipmaps(10), pass((10, false), (0, false))];
        let planned = plan(&mut commands, lookup);
        assert_eq!(planned.first().map(|step| step.discard), Some(0));
        assert!(matches!(
            commands.get(2),
            Some(Command::BeginPass { colors, .. })
                if colors.first().is_some_and(|color| color.clear.is_none())
        ));
    }

    #[test]
    fn mipmaps_keep_base_store_for_later_sample() {
        let mut commands = [
            pass((10, false), (0, false)),
            Command::EndPass,
            mipmaps(10),
            sample(11),
        ];
        assert_eq!(steps(&mut commands).first().map(|step| step.1), Some(0));
    }

    #[test]
    fn mipmap_output_can_be_discarded_by_later_full_overwrite() {
        let mut commands = [
            pass((11, false), (0, false)),
            Command::EndPass,
            mipmaps(10),
            pass((11, true), (0, false)),
        ];
        assert_eq!(steps(&mut commands).first().map(|step| step.1), Some(1));
    }

    #[test]
    fn metalfx_breaks_merge_between_passes() {
        let mut commands = [
            pass((10, true), (20, true)),
            Command::EndPass,
            metalfx(10, 20, 30),
            pass((10, false), (20, false)),
            Command::EndPass,
        ];
        assert_eq!(steps(&mut commands).get(3).map(|step| step.0), Some(false));
    }

    #[test]
    fn metalfx_prevents_clear_folding_across_input_read_and_output_write() {
        let mut commands = [
            clear(10),
            metalfx(10, 20, 30),
            pass((30, false), (0, false)),
        ];
        let planned = plan(&mut commands, lookup);
        assert_eq!(planned.first().map(|step| step.discard), Some(0));
        assert!(
            matches!(commands.get(2), Some(Command::BeginPass { colors, .. })
            if colors.first().is_some_and(|color| color.clear.is_none()))
        );
    }

    #[test]
    fn metalfx_preserves_input_pass_before_later_gui_output_read() {
        let mut commands = [
            pass((10, true), (20, true)),
            Command::EndPass,
            metalfx(10, 20, 30),
            sample(30),
        ];
        let planned = steps(&mut commands);
        assert_eq!(planned.first().map(|step| step.1), Some(0));
    }

    #[test]
    fn composition_output_remains_for_gui_and_surface_present() {
        let mut commands = [
            pass((10, true), (20, true)),
            Command::EndPass,
            metalfx(10, 20, 30),
            pass((30, false), (0, false)),
            Command::EndPass,
            Command::BlitToSurface(30),
        ];
        let planned = steps(&mut commands);
        assert_eq!(planned.get(3).map(|step| step.0), Some(false));
        assert_eq!(planned.get(3).map(|step| step.1), Some(0));
    }

    #[test]
    fn feature_cull_breaks_merge_and_keeps_depth() {
        let mut commands = [
            pass((10, true), (20, true)),
            Command::EndPass,
            feature(21),
            pass((10, false), (20, false)),
            Command::EndPass,
        ];
        let planned = steps(&mut commands);
        assert_eq!(planned.first().map(|step| step.1), Some(0));
        assert_eq!(planned.get(3).map(|step| step.0), Some(false));
    }

    #[test]
    fn feature_cull_prevents_depth_clear_folding() {
        let mut commands = [clear(21), feature(21), pass((11, false), (21, false))];
        let planned = plan(&mut commands, lookup);
        assert_eq!(planned.first().map(|step| step.discard), Some(0));
        assert!(matches!(
            commands.get(2),
            Some(Command::BeginPass { depth, .. }) if depth.clear.is_none()
        ));
    }

    #[test]
    fn cull_breaks_merge_and_keeps_depth() {
        let mut commands = [
            pass((10, true), (20, true)),
            Command::EndPass,
            cull(21),
            pass((10, false), (20, false)),
            Command::EndPass,
        ];
        let planned = steps(&mut commands);
        assert_eq!(planned.first().map(|step| step.1), Some(0));
        assert_eq!(planned.get(3).map(|step| step.0), Some(false));
    }

    #[test]
    fn expand_breaks_merge_between_passes() {
        let mut commands = [
            pass((10, true), (20, true)),
            Command::EndPass,
            expand(),
            pass((10, false), (20, false)),
            Command::EndPass,
        ];
        let planned = steps(&mut commands);
        assert_eq!(planned.get(3).map(|step| step.0), Some(false));
    }

    #[test]
    fn does_not_fold_clear_read_by_cull() {
        let mut commands = [clear(21), cull(21), pass((11, false), (21, false))];
        let planned = plan(&mut commands, lookup);
        assert_eq!(planned.first().map(|step| step.discard), Some(0));
        assert!(matches!(
            commands.get(2),
            Some(Command::BeginPass { depth, .. }) if depth.clear.is_none()
        ));
    }

    #[test]
    fn folds_clear_into_pass_that_already_clears() {
        let mut commands = [clear(11), pass((11, true), (0, false))];
        let planned = plan(&mut commands, lookup);
        assert_eq!(planned.first().map(|step| step.discard), Some(1));
        assert!(matches!(
            commands.get(1),
            Some(Command::BeginPass { colors, .. })
                if colors.first().is_some_and(|color| color.clear.is_some())
        ));
    }
}
