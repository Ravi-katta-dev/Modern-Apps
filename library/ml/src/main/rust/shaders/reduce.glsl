// Workgroup-wide reductions for the row-parallel mode of the reducing shaders.
//
// Included after `common.glsl` by `layernorm.comp`, `rmsnorm.comp`, `softmax.comp` and
// `softmax_prefix.comp`, each of which must enable `GL_KHR_shader_subgroup_basic` and
// `GL_KHR_shader_subgroup_arithmetic` before its first include - `#extension` has to precede
// every declaration.
//
// # Why a row mode exists
//
// Those shaders were written for one invocation per row, each walking the whole row on its own.
// That is right when there are thousands of rows, and it leaves the GPU nearly idle when there
// are a handful: a decode step normalises **one** position of 1,024 channels, so a single thread
// does 3,072 dependent loads while every other lane on the device waits. On a Pixel 8 that made
// NLLB's 37 layer norms 47% of a decode step at 790 us each, and Gemma's 241 RMS norms 200 ms of
// a 409 ms token.
//
// With `p.kw == 1` a whole 64-lane workgroup owns one row instead: lanes stride the row, reduce
// in registers with a subgroup op, then across the workgroup's subgroups through `reduce_partial`.
// `p.kw` is otherwise unused by these shaders, `p.count` stays the row count, and the work per row
// is unchanged - so the reference interpreter, `Kind::arena_reads` and the barrier schedule need
// no change. `Builder` picks the mode; see `ROW_REDUCE_MAX_ROWS` in `nets`.
//
// Every call is reached by the whole workgroup: the row index is per workgroup, so an early
// `return` on it is uniform, and `p.kw` is a push constant.

// One slot per subgroup. 64 covers a subgroup size of 1, which no device has but costs nothing.
shared float reduce_partial[64];

// The row this workgroup owns in row mode, across the same 2-D grid `global_index` flattens.
uint reduce_row() {
    return gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x;
}

// Whether this dispatch runs one workgroup per row.
bool reduce_row_mode() {
    return p.kw == 1u;
}

// The sum of `value` over every lane of the workgroup, returned to every lane.
float workgroup_sum(float value) {
    float summed = subgroupAdd(value);
    if (subgroupElect()) {
        reduce_partial[gl_SubgroupID] = summed;
    }
    barrier();
    float total = 0.0;
    for (uint i = 0u; i < gl_NumSubgroups; i++) {
        total += reduce_partial[i];
    }
    // The next reduction reuses the slots, so nobody may write them until everyone has read.
    barrier();
    return total;
}

// The maximum of `value` over every lane of the workgroup, returned to every lane.
float workgroup_max(float value) {
    float top = subgroupMax(value);
    if (subgroupElect()) {
        reduce_partial[gl_SubgroupID] = top;
    }
    barrier();
    float peak = reduce_partial[0];
    for (uint i = 1u; i < gl_NumSubgroups; i++) {
        peak = max(peak, reduce_partial[i]);
    }
    barrier();
    return peak;
}
