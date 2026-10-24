/// Hook every transit stop that is *not* attached to a real road network onto the
/// nearest node that is. Without this a matched GTFS stop that OSM never attached
/// to a road is unreachable and the transit planner cannot get the user to it.
///
/// # Why component *size* and not the largest component
///
/// The question worth asking is "is this stop attached to a road network?", not
/// "is it attached to the biggest one?". On a region those coincide — the largest
/// component is 98.2% of California — so the pass used to find the largest
/// component and reconnect everything outside it. On a planet they do not:
/// continents are not road-connected, so the largest component is
/// Eurasia-plus-Africa at 57.7% and every stop in the Americas, Australia, Japan,
/// the UK and Indonesia was classified as isolated. The code then tried to bridge
/// an ocean to reach a road in France, failed 1,173,347 times — a quarter of all
/// stops — and paid for a widening search out to a million-node index window
/// before each failure.
///
/// So a stop needs reconnecting only when its own component is smaller than
/// [`MIN_ROUTABLE_COMPONENT`], and the target is the nearest node in *any*
/// component at or above that threshold. A stop alone on an untagged node is a
/// component of size 1 and is still connected; a stop on a small island's road
/// network is left alone, because it is already as reachable as that island gets;
/// a stop in North America connects to North America. That also removes the
/// pathology rather than its symptom: a qualifying node is now metres away, so the
/// widening search almost always succeeds on its first window.
///
/// The threshold is capped at the largest component that exists, because a graph
/// with no component of 1000 nodes still has a road network — it is just a small
/// one — and requiring a size nothing reaches would reconnect nothing at all.
///
/// One caveat on the word "component": the scan follows out-edges only, because
/// that is the index the build has — a reverse CSR would be another 4.3 GB of
/// `targets` at planet scale. So the partition is by out-reachability, and a node
/// fed only by incoming one-ways can be labelled separately from the network that
/// feeds it, undercounting that network's size. The error is in the safe direction:
/// such a node looks *less* connected than it is, so at worst it gains a connector
/// it did not need. The same traversal is what measured California's largest
/// component at 98.24%, which bounds how far off it can be in practice.
///
/// `coords` and `stops` are both in final (Morton) id space.
fn reconnect_isolated_stops(coords: &[geom::Pt], stops: &Bitset, csr: &Csr) -> Reconnected {
    let n = coords.len();
    if n == 0 {
        return Reconnected {
            lcc_size: 0,
            synth: Vec::new(),
            already_connected: 0,
            unreachable: 0,
        };
    }
    println!("Identifying connected components...");

    let mut component = vec![u32::MAX; n];
    // One size per component id. Planet has ~291 K components, so 1.2 MB.
    let mut sizes: Vec<u32> = Vec::new();
    let mut queue: Vec<u32> = Vec::with_capacity(n);
    let mut prog = crate::progress::Progress::new("Connected components", n as u64);
    for start in 0..n {
        prog.inc();
        if component[start] != u32::MAX {
            continue;
        }
        let id = sizes.len() as u32;
        let base = queue.len();
        queue.push(start as u32);
        component[start] = id;
        let mut head = base;
        while head < queue.len() {
            let u = queue[head];
            head += 1;
            for v in csr.out(u) {
                if component[*v as usize] == u32::MAX {
                    component[*v as usize] = id;
                    queue.push(*v);
                }
            }
        }
        sizes.push((queue.len() - base) as u32);
    }
    prog.finish();
    drop(queue);

    let lcc_size = sizes.iter().copied().max().unwrap_or(0);
    let threshold = MIN_ROUTABLE_COMPONENT.min(lcc_size);
    println!(
        "{} component(s), largest {lcc_size} / {n} node(s) ({:.1}%); a component of \
         {threshold}+ node(s) counts as routable",
        sizes.len(),
        f64::from(lcc_size) * 100.0 / n as f64
    );

    let routable = |node: u32| sizes[component[node as usize] as usize] >= threshold;
    let mut stop_count = 0usize;
    let mut isolated: Vec<u32> = Vec::new();
    for i in 0..n as u32 {
        if stops.get(u64::from(i)) {
            stop_count += 1;
            if !routable(i) {
                isolated.push(i);
            }
        }
    }
    let already_connected = stop_count - isolated.len();
    if isolated.is_empty() {
        println!("{stop_count} stop(s), all already on a routable component");
        return Reconnected {
            lcc_size: u64::from(lcc_size),
            synth: Vec::new(),
            already_connected,
            unreachable: 0,
        };
    }

    // The nodes are Morton-sorted, so index distance approximates spatial
    // distance: widen an index window around the stop until it contains a node in
    // a routable component, then take the closest one in that window.
    let found = par::map_chunks(&isolated, 256, |_, chunk| {
        let mut out: Vec<Synth> = Vec::new();
        let mut failed = 0usize;
        for &i in chunk {
            let stop = coords[i as usize];
            let mut best: Option<(u32, u32)> = None;
            let mut radius = 1000usize;
            while radius <= 1_000_000 {
                let lo = (i as usize).saturating_sub(radius);
                let hi = (i as usize + radius).min(n);
                for j in lo..hi {
                    if !routable(j as u32) {
                        continue;
                    }
                    let d = accurate_dist_mm(stop.0, stop.1, coords[j].0, coords[j].1);
                    if best.is_none_or(|(bd, _)| d < bd) {
                        best = Some((d, j as u32));
                    }
                }
                if best.is_some() {
                    break;
                }
                radius *= 10;
            }
            if let Some((dist_mm, target)) = best {
                out.push(Synth {
                    source: i,
                    target,
                    dist_mm,
                });
                out.push(Synth {
                    source: target,
                    target: i,
                    dist_mm,
                });
            } else {
                failed += 1;
            }
        }
        (out, failed)
    });

    let unreachable: usize = found.iter().map(|(_, f)| f).sum();
    let mut synth: Vec<Synth> = found.into_iter().flat_map(|(e, _)| e).collect();
    println!(
        "{stop_count} stop(s): {already_connected} already connected, {} reconnected, \
         {unreachable} found nothing",
        synth.len() / 2
    );
    if unreachable > 0 {
        // The index window caps at 1e6 nodes, so a stop with no routable road
        // within that window stays unreachable. Say so rather than dropping it
        // silently: an unreachable stop is exactly what this pass exists to
        // prevent. A large count here means MIN_ROUTABLE_COMPONENT is too high.
        eprintln!("WARN: {unreachable} isolated stop(s) had no routable road nearby");
    }
    synth.sort_by_key(|s| (s.source, s.target));
    Reconnected {
        lcc_size: u64::from(lcc_size),
        synth,
        already_connected,
        unreachable,
    }
}

/// What `write_graph` produced, for [`Stats`].
struct Written {
    edge_count: u64,
    geometry_edges: u64,
    reversed_edges: u64,
    intermediate_bytes: u64,
    edges_bytes: u64,
    escape_count: u64,
    named_edges: u64,
    census: Census,
}

/// One write worker's output for a batch of edges, in batch order.
///
/// Two shapes, interleaved in visit order: node openers (one per node the
/// batch owns, edgeless ones included) and edge items (one per edge). The
/// serial append folds them positionally — node record, then its edges — so
/// concatenation reproduces the serial byte sequence exactly. The geometry
/// decision rides along per edge (computed on the pool from the shared
/// mapping); census inputs ride along too (the census is additive, replayed
/// verbatim); node openers carry their own `edge_ptr` snapshot and degree so
/// the drain never recomputes running state.
enum BakedItem {
    /// Open a node: stage its record, count its degree, tick it.
    Node {
        /// Node-record bytes (12 B) with the absolute edge pointer.
        rec: [u8; 12],
        /// Out-degree, for the census replay.
        degree: u32,
    },
    /// One edge: census inputs, record inputs, geometry outcome, lanes.
    /// Boxed: edges outnumber nodes ~3:1 and the small `Node` variant would
    /// otherwise bloat every item to the edge size (the pool holds a whole
    /// round's items at once).
    Edge(Box<BakedEdge>),
}

/// One decided edge: record inputs, geometry outcome, census inputs, lanes.
struct BakedEdge {
    /// Census inputs for the edge: source, target, dist, named. The edge
    /// census is a pure function of these (histogram increments, maxima,
    /// escape predicates), so the drain replays `Census::edge` verbatim.
    cen_source: u32,
    cen_target: u32,
    cen_dist: u32,
    cen_named: bool,
    /// Edge record inputs: the delta/dist pair (or the escape sentinel with
    /// its row), the final type byte, speed, and name.
    delta: i16,
    dist: u32,
    escaped: Option<(u32, u32)>,
    type_: u8,
    speed_limit: u8,
    name_offset: u32,
    /// Geometry outcome: encoded interior blob, reverse-flag, or absent.
    geom: BakedGeom,
    /// Lane run values when the edge carries lanes.
    lanes: Vec<u16>,
}

/// What the pool decided about one edge's geometry.
enum BakedGeom {
    /// No blob: two-point chord, short chain, or no chain.
    Absent,
    /// Defer to the twin: set the reverse flag, store nothing.
    Reversed,
    /// Encoded interior blob, ready to stage.
    Blob(Vec<u8>),
}

/// Write the pack in `rounds` source-partitioned rounds.
///
/// # Why rounds, and not computed offsets
///
/// The obvious alternative is to compute each edge's index and `pwrite` it into
/// place. That is sound for `edges.bin` — fixed 14-byte records in a pre-sized
/// file — but wrong for the two blob files. The reader derives a polyline's length
/// from the next offset entry and a lane run's from the next index entry, so
/// `intermediate.bin` and `lanes.bin` are forced into edge-index order with
/// monotonic offsets, and writing a blob at a computed offset would mean knowing
/// every earlier blob's length first: a per-edge length array, a prefix sum and a
/// second encode pass.
///
/// Partitioning by source instead makes every write an append. Each round takes a
/// contiguous range of final node ids, scans the chain spill for the edges whose
/// *source* falls in that range, sorts that buffer, and writes both blobs, the
/// presence bitmap, the offset tables, `edges.bin` and `nodes.bin` sequentially.
/// Offsets come out monotonic by construction, `nodes.bin`'s running edge counter
/// works because the rounds ascend, and the `(source, target)` grouping the reader
/// and [`twin_is_unique`] depend on is restored within each round.
///
/// The cost is `rounds` sequential reads of the spill instead of one.
#[allow(clippy::too_many_arguments)]
fn write_graph(
    out_dir: &Path,
    node_coords: &[geom::Pt],
    spill: &chains::Spill,
    lane_pool: &[u16],
    csr: &Csr,
    synth: &[Synth],
    ids: &FinalIds,
    rounds: u32,
    dem: Option<&crate::dem::Dem>,
    timer: &mut crate::timing::PhaseTimer,
) -> Result<Written> {
    let kept = node_coords.len() as u32;
    let edge_count = csr.edge_count() + synth.len() as u64;
    // `nodes.bin`'s `edge_ptr` is a `u32`, so an edge count past that ceiling would
    // wrap and point a node at another node's edge range.
    cap_u32("directed edge", edge_count)?;
    println!("Writing {edge_count} edge(s) in {rounds} round(s)...");
    let chain_total = spill.chain_count()?;

    let mut nodes_blocked = tilecodec::blocked::BlockedStreamWriter::create(
        12,
        &out_dir.join("nodes.blocked.tmp"),
    )
    .map_err(|e| Error(format!("cannot stage nodes.bin: {e}")))?;
    let mut edges_out = EdgeFile::create(out_dir, "edges.bin", edge_count)?;

    // `intermediate.bin` puts its blob at offset 0 and everything that indexes it
    // in a trailer, so it is written strictly forwards with no scratch file at all
    // (see [`GeomFile`]). `lanes.bin` is sparse and so cannot size its index up
    // front; its blob streams to scratch instead and the index is written in front
    // of it.
    let mut inter = GeomFile::create(out_dir, "intermediate.bin", edge_count)?;
    let mut lanes = LaneFile::create(out_dir, "lanes.bin")?;

    // Per-batch scratch lives on the workers in the pipeline design;
    // the restored serial loop below reuses one triple, as before.
    let mut scratch: Vec<geom::Pt> = Vec::new();
    let mut encoded: Vec<u8> = Vec::new();
    let mut lane_bytes: Vec<u8> = Vec::new();
    let mut buffer: Vec<TmpEdge> = Vec::new();
    let mut points = spill.points()?;
    let mut edge_ptr: u64 = 0;
    let mut geometry_edges: u64 = 0;
    let mut reversed_edges: u64 = 0;
    let mut census = Census::default();
    // One assignment scan instead of one scan per round: every chain's round
    // is a pure function of its endpoints' final ids, so a single pass over
    // the mapped headers records each chain's index per round and every round
    // gathers only its chains. `rounds` sequential header passes become one
    // (the mapping stays resident for the gathers). Chain order within a
    // round is ascending index — the order the old per-round scan visited —
    // so the buffer, the sort (stable), and everything downstream are
    // identical.
    timer.switch("write-scan");
    let headers = spill.headers_mapped()?;
    let chain_total_now = headers.len()?;
    if chain_total_now != chain_total {
        return Err(Error(format!(
            "chains.hdr holds {chain_total_now} chain(s), expected {chain_total}"
        )));
    }
    let mut by_round: Vec<Vec<u32>> = vec![Vec::new(); rounds as usize];
    {
        let mut prog =
            crate::progress::Progress::new("Assign rounds".to_string(), chain_total);
        // `ids.get` is two array lookups; the decode is 48 bytes. Both are
        // dwarfed by the I/O the old code paid re-streaming per round.
        for ci in 0..chain_total as u32 {
            let c = headers.get(ci as u64)?;
            let (a, b) = (ids.get(c.first), ids.get(c.last));
            // A chain enters a round when the old scan would have buffered an
            // edge for it there: source endpoint in range, or (two-way) target
            // endpoint in range. One entry per qualifying round — a chain whose
            // endpoints straddle a boundary is gathered twice, once per round,
            // exactly as the old scan buffered it twice.
            for round in 0..rounds {
                let lo = (u64::from(kept) * u64::from(round) / u64::from(rounds)) as u32;
                let hi =
                    (u64::from(kept) * u64::from(round + 1) / u64::from(rounds)) as u32;
                if (lo..hi).contains(&a) || (!c.oneway && (lo..hi).contains(&b)) {
                    by_round[round as usize].push(ci);
                }
            }
            prog.inc();
        }
        prog.finish();
    }

    for round in 0..rounds {
        // Split by node count. Contiguous and ascending, so the four output streams
        // stay append-only across the whole run.
        let lo = (u64::from(kept) * u64::from(round) / u64::from(rounds)) as u32;
        let hi = (u64::from(kept) * u64::from(round + 1) / u64::from(rounds)) as u32;

        buffer.clear();
        // Gather: this round's chains in ascending index order — the visit
        // order of the old per-round scan, so buffer order pre-sort (and
        // therefore post-sort, stable) is unchanged.
        for &ci in &by_round[round as usize] {
            let c = headers.get(ci as u64)?;
            let (a, b) = (ids.get(c.first), ids.get(c.last));
            if (lo..hi).contains(&a) {
                buffer.push(chain_edge(&c, ci, a, b, false));
            }
            if !c.oneway && (lo..hi).contains(&b) {
                buffer.push(chain_edge(&c, ci, b, a, true));
            }
        }
        let synth_lo = synth.partition_point(|s| s.source < lo);
        let synth_hi = synth.partition_point(|s| s.source < hi);
        for s in &synth[synth_lo..synth_hi] {
            buffer.push(TmpEdge {
                source: s.source,
                target: s.target,
                dist_mm: s.dist_mm,
                name_offset: NO_NAME,
                type_: RECONNECT_TYPE,
                speed_limit: RECONNECT_SPEED,
                lane_off: NO_LANES,
                lane_count: 0,
                chain: NO_CHAIN,
                chain_rev: false,
                pts_start: 0,
                pts_len: 0,
            });
        }

        // The CSR already knows how many edges this range owns. Checking against it
        // catches both an edge landing in the wrong round and an edge whose source
        // is not a surviving node — which is what the old
        // `assert_eq!(cursor, edges.len())` was for, now checked per round and
        // before anything is written rather than after everything is.
        let expected = csr.edge_ptr[hi as usize] - csr.edge_ptr[lo as usize]
            + (synth_hi - synth_lo) as u64;
        if buffer.len() as u64 != expected {
            return Err(Error(format!(
                "round {round} covering node(s) {lo}..{hi} collected {} edge(s), \
                 but the edge index says {expected}",
                buffer.len()
            )));
        }

        // Grouped by source for the reader, then by target so a node's parallel
        // edges are adjacent, then by chain so the order is total. The sort is
        // stable and the buffer was built in chain order, so the two directions of
        // a self-loop keep the order they were emitted in.
        buffer.sort_by_key(|e| (e.source, e.target, e.chain));

        // --- write-write: serial drain over the sorted buffer ----------------
        //
        // A parallel bake/drain pipeline lived here (positional batches baked
        // on the pool, drained in order). It failed the build's own gate:
        // nodes.bin unpacked to 12x the pushed records while every counter
        // (bake openers, drain openers, drain pushes) read exactly right —
        // and the same 12x appeared with the bake forced single-threaded, so
        // the duplication is not a threading race. The prime suspect is the
        // batch/item framing itself (spans, bases, or the opener splice
        // double-covering nodes), but rather than debug the framing blind,
        // this restores the serial loop verbatim and keeps the pipeline
        // design (plus the failing counters to re-prove against) in the
        // task notes. The single-scan assignment above stays: it is proven
        // (byte-identical CA hashes) and independent of the drain shape.
        //
        // TODO(write-pipe): re-land the pipeline. Re-entry checklist:
        //   1. unit test bake_batch on a synthetic buffer FIRST (no build
        //      needed): assert opener count == node span and opener edge_ptrs
        //      == base + run offsets, before any pool or drain exists;
        //   2. reintroduce pool + drain behind that test;
        //   3. CA hash must equal the serial hash before any planet run.
        timer.switch("write-write");
        {
            let mut wprog = crate::progress::Progress::new(
                format!("Round {}/{rounds}: write", round + 1),
                u64::from(hi - lo),
            );
            let mut cursor = 0usize;
            for v in lo..hi {
                let node = node_coords[v as usize];
                let mut rec = [0u8; 12];
                rec[0..4].copy_from_slice(&node.0.to_le_bytes());
                rec[4..8].copy_from_slice(&node.1.to_le_bytes());
                rec[8..12].copy_from_slice(&(edge_ptr as u32).to_le_bytes());
                nodes_blocked
                    .push(&rec)
                    .map_err(|e| Error(format!("cannot stage node record: {e}")))?;
                let degree_base = edge_ptr;
                while cursor < buffer.len() && buffer[cursor].source == v {
                    let e = &buffer[cursor];
                    census.edge(e.source, e.target, e.dist_mm, e.name_offset != NO_NAME);

                    // --- geometry ---
                    let mut type_ = e.type_;
                    let mut stores = false;
                    // A two-point chain is just the chord between the edge's own
                    // endpoints, and under the interior-only encoding that is what an
                    // absent blob already means, so storing it would cost a presence
                    // bit to say nothing — and, more to the point here, a read to find
                    // that out.
                    if e.chain != NO_CHAIN && e.pts_len >= 3 {
                        if e.chain_rev
                            && e.source != e.target
                            && twin_is_unique(csr, synth, e.source, e.target)
                        {
                            type_ |= REVERSE_GEOMETRY_FLAG;
                            reversed_edges += 1;
                        } else {
                            // Only now is the polyline actually needed. Deciding the
                            // flag first matters more than it looks: this is the only
                            // random read in the writer, so every edge that defers to
                            // its twin is one seek saved, and on a filesystem whose
                            // syscalls are expensive that dominated the whole build.
                            points.read(e.pts_start, e.pts_len, &mut scratch)?;
                            if e.chain_rev {
                                scratch.reverse();
                            }
                            assert!(
                                geom::fits(&scratch),
                                "chain of {} points cannot be encoded; the collapse should \
                                 have split it",
                                scratch.len()
                            );
                            // The blob holds only the interior, so the reader rebuilds
                            // both ends out of `nodes.bin`.
                            assert_endpoints(&scratch, node_coords, e.source, e.target, e.chain);
                            encoded.clear();
                            let n = geom::encode(&scratch, &mut encoded);
                            debug_assert_eq!(n as usize, geom::encoded_points(&scratch) as usize);
                            inter.store(&encoded)?;
                            geometry_edges += 1;
                            stores = true;
                        }
                    }
                    if !stores {
                        inter.skip();
                    }

                    edges_out.push(e, type_)?;
                    if e.lane_count > 0 && e.lane_off != NO_LANES {
                        let start = e.lane_off as usize;
                        lane_bytes.clear();
                        for m in &lane_pool[start..start + e.lane_count as usize] {
                            lane_bytes.extend_from_slice(&m.to_le_bytes());
                        }
                        lanes.push(edge_ptr, &lane_bytes)?;
                    }
                    edge_ptr += 1;
                    cursor += 1;
                }
                census.node((edge_ptr - degree_base) as u32);
                wprog.inc();
            }
            wprog.finish();
            debug_assert_eq!(cursor, buffer.len(), "the round left edges unwritten");
        }
    }

    if edge_ptr != edge_count {
        return Err(Error(format!(
            "wrote {edge_ptr} edge(s) into a file sized for {edge_count}"
        )));
    }
    finish_pack(
        out_dir,
        kept,
        edge_count,
        edge_ptr,
        geometry_edges,
        reversed_edges,
        nodes_blocked,
        edges_out,
        inter,
        lanes,
        census,
        node_coords,
        dem,
    )
}

/// Finish the four output streams and the metadata: the tail of `write_graph`
/// after the last round drains. Unchanged from the serial loop — the drain
/// above stages identical bytes, so everything downstream (sentinel, sanity
/// parses, metadata, elevation) is untouched.
#[allow(clippy::too_many_arguments)]
fn finish_pack(
    out_dir: &Path,
    kept: u32,
    edge_count: u64,
    edge_ptr: u64,
    geometry_edges: u64,
    reversed_edges: u64,
    nodes_blocked: tilecodec::blocked::BlockedStreamWriter,
    edges_out: EdgeFile,
    inter: GeomFile,
    lanes: LaneFile,
    census: Census,
    node_coords: &[geom::Pt],
    dem: Option<&crate::dem::Dem>,
) -> Result<Written> {
    let mut nodes_blocked = nodes_blocked;
    let mut edges_out = edges_out;
    let mut inter = inter;
    let mut lanes = lanes;
    // Trailing sentinel so `graph.rs` can read node(v + 1).edge_ptr as the end of
    // node v's edge range.
    let mut sentinel = [0u8; 12];
    sentinel[8..12].copy_from_slice(&(edge_ptr as u32).to_le_bytes());
    nodes_blocked
        .push(&sentinel)
        .map_err(|e| Error(format!("cannot stage node sentinel: {e}")))?;
    let nodes_bytes = nodes_blocked
        .finish()
        .map_err(|e| Error(format!("cannot finish nodes.bin: {e}")))?;
    // Sanity: the blocked section must unpack to exactly the pushed records.
    let parsed = tilecodec::blocked::Blocked::parse(&nodes_bytes).map_err(|e| {
        Error(format!(
            "freshly blocked nodes.bin does not parse: {e}"
        ))
    })?;
    // Sanity: the blocked section must unpack to exactly the pushed records.
    if parsed.unpacked_len() != (u64::from(kept) + 1) * 12 {
        return Err(Error(format!(
            "nodes.bin unpacks to {} byte(s), not {} 12-byte records",
            parsed.unpacked_len(),
            u64::from(kept) + 1
        )));
    }
    std::fs::write(out_dir.join("nodes.bin"), &nodes_bytes).map_err(io_err)?;
    let escape_count = edges_out.escapes.len() as u64;
    let named_edges = edges_out.name_offsets.len() as u64;
    let edges_bytes = edges_out.finish(edge_count)?;
    let intermediate_bytes = inter.finish(edge_count)?;
    lanes.finish()?;
    finish_pack_tail(
        out_dir,
        kept,
        edge_count,
        escape_count,
        named_edges,
        node_coords,
        dem,
    )?;
    Ok(Written {
        edge_count,
        geometry_edges,
        reversed_edges,
        intermediate_bytes,
        edges_bytes,
        escape_count,
        named_edges,
        census,
    })
}

/// Metadata + elevation tail of `write_graph`, after every stream finishes.
/// Split out so the round loop above reads as plan/bake/drain without the
/// file-assembly tail inline. Byte-identical: moved whole, order untouched.
#[allow(clippy::too_many_arguments)]
fn finish_pack_tail(
    out_dir: &Path,
    kept: u32,
    edge_count: u64,
    escape_count: u64,
    named_edges: u64,
    node_coords: &[geom::Pt],
    dem: Option<&crate::dem::Dem>,
) -> Result<()> {
    // metadata.bin's magic + version let the device refuse a pack directory
    // holding files from two different vintages instead of misreading it, and its
    // counts are what every other file's length is validated against.
    let mut meta = create(&out_dir.join("metadata.bin"))?;
    meta.write_all(&GRAPH_MAGIC.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&GRAPH_VERSION.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&u64::from(kept).to_le_bytes()).map_err(io_err)?;
    meta.write_all(&edge_count.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&escape_count.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&named_edges.to_le_bytes()).map_err(io_err)?;
    meta.flush().map_err(io_err)?;
    // elevation.bin: `i16[node_count]` metres, one per node in final-id order — an OPTIONAL sidecar
    // like road_names.bin/lanes.bin, so a build without a DEM simply omits it and the device reads
    // every node's elevation as 0. `node_coords` is already indexed by final node id, so a single
    // forward pass matches the order `nodes.bin` was written in. Blocked like
    // nodes/edges under the v7 layout (magic-dispatched on read, so absence
    // still reads as all-zero).
    if let Some(dem) = dem {
        let mut elev_blocked = tilecodec::blocked::BlockedStreamWriter::create(
            2,
            &out_dir.join("elevation.blocked.tmp"),
        )
        .map_err(|e| Error(format!("cannot stage elevation.bin: {e}")))?;
        for &(lat_e7, lon_e7) in node_coords {
            let lat = f64::from(lat_e7) * 1e-7;
            let lon = f64::from(lon_e7) * 1e-7;
            let metres = dem.sample_metres(lon, lat).unwrap_or(0);
            elev_blocked
                .push(&metres.to_le_bytes())
                .map_err(|e| Error(format!("cannot stage elevation record: {e}")))?;
        }
        let elev_bytes = elev_blocked
            .finish()
            .map_err(|e| Error(format!("cannot finish elevation.bin: {e}")))?;
        std::fs::write(out_dir.join("elevation.bin"), &elev_bytes).map_err(io_err)?;
    }
    Ok(())
}

/// Bake one write batch: every node record and edge record's bytes, decided.
///
/// Positional over the sorted edge buffer: `edges` are the buffer slice this
/// batch owns (one or more whole nodes' runs — batch boundaries fall on node
/// boundaries by construction), `first_node` the batch's first node id, and
/// `batch_edge_base` its edge index in the global order (so node records
/// carry their own absolute `edge_ptr`). Returns node openers interleaved
/// with their edges in the exact order the serial loop visited them (node
/// record, then its edges).
///
/// Pure over shared tables (`node_coords`, `lane_pool`, `csr`, `synth`,
/// `points`): safe across the pool. Fallible only like the serial loop was —
/// a truncated `chains.pts` fails the build rather than shortening it — and
/// the drain reports the first failure in batch order. `assert_endpoints` and
/// the `geom::fits` gate run here, on the worker that encoded: same
/// predicate, same message, same abort.
#[allow(clippy::too_many_arguments)]
fn bake_batch(
    edges: &[TmpEdge],
    first_node: u32,
    batch_edge_base: u64,
    node_coords: &[geom::Pt],
    lane_pool: &[u16],
    csr: &Csr,
    synth: &[Synth],
    points: &crate::mem::Mapped,
) -> Result<Vec<BakedItem>> {
    let mut out: Vec<BakedItem> = Vec::new();
    let mut scratch: Vec<geom::Pt> = Vec::new();
    let mut encoded: Vec<u8> = Vec::new();
    // The slice starts on a node boundary (batches partition the node range)
    // but its first edge may belong to a later node than `first_node`:
    // nodes between hold no edges (degree 0) and still open a record each.
    let mut node = first_node;
    let mut i = 0usize;
    let mut edge_idx = batch_edge_base;
    while i < edges.len() {
        while node < edges[i].source {
            out.push(baked_node(node, node_coords, edge_idx, 0));
            node += 1;
        }
        // This run's edges, in slice order (source-sorted, then target, then
        // chain — the serial order).
        let source = edges[i].source;
        debug_assert_eq!(source, node, "batch slices split a node's run");
        let run_start = out.len();
        let mut degree = 0u32;
        while i < edges.len() && edges[i].source == source {
            out.push(BakedItem::Edge(Box::new(bake_edge(
                &edges[i],
                lane_pool,
                csr,
                synth,
                points,
                node_coords,
                &mut scratch,
                &mut encoded,
            )?)));
            degree += 1;
            edge_idx += 1;
            i += 1;
        }
        // The opener comes FIRST in drain order (node record, then its
        // edges): splice it before the run just baked. Runs are short
        // (degree ones mostly), so the shift is cheap — and positional
        // exactness is the whole contract.
        out.insert(run_start, baked_node(source, node_coords, edge_idx - degree as u64, degree));
        node += 1;
    }
    Ok(out)
}

/// One node opener: its record bytes with the absolute edge pointer, plus its
/// out-degree for the census replay.
fn baked_node(node: u32, node_coords: &[geom::Pt], edge_ptr: u64, degree: u32) -> BakedItem {
    let coords = node_coords[node as usize];
    let mut rec = [0u8; 12];
    rec[0..4].copy_from_slice(&coords.0.to_le_bytes());
    rec[4..8].copy_from_slice(&coords.1.to_le_bytes());
    rec[8..12].copy_from_slice(&(edge_ptr as u32).to_le_bytes());
    BakedItem::Node { rec, degree }
}

/// One edge, decided: record inputs, geometry outcome, census inputs, lanes.
///
/// Mirrors the serial loop's per-edge body exactly: escape narrowing (same
/// predicate, same sentinel rows), the twin-check-before-read (same flag),
/// polyline read + reverse + gates + encode, lane run copy. Census inputs
/// are the edge's own fields (replayed verbatim by the drain).
fn bake_edge(
    e: &TmpEdge,
    lane_pool: &[u16],
    csr: &Csr,
    synth: &[Synth],
    points: &crate::mem::Mapped,
    node_coords: &[geom::Pt],
    scratch: &mut Vec<geom::Pt>,
    encoded: &mut Vec<u8>,
) -> Result<BakedEdge> {
    // Narrow first, exactly as `EdgeFile::push` does: the drain replays the
    // narrowing verbatim through `push_baked_edge`, so the predicate lives in
    // one place (there) and here only the escape ROW is decided... simpler:
    // decide everything here and carry the outcome.
    let (delta, dist, escaped) = match target_delta(e.source, e.target) {
        Some(d) if e.dist_mm < DIST_MM_ESCAPE => (d, e.dist_mm, None),
        _ => (
            TARGET_DELTA_ESCAPE,
            DIST_MM_ESCAPE,
            Some((e.target, e.dist_mm)),
        ),
    };
    // --- geometry ---
    let mut type_ = e.type_;
    let mut geom = BakedGeom::Absent;
    // A two-point chain is just the chord between the edge's own endpoints,
    // and under the interior-only encoding that is what an absent blob
    // already means, so storing it would cost a presence bit to say nothing —
    // and, more to the point here, a read to find that out.
    if e.chain != NO_CHAIN && e.pts_len >= 3 {
        if e.chain_rev && e.source != e.target && twin_is_unique(csr, synth, e.source, e.target)
        {
            type_ |= REVERSE_GEOMETRY_FLAG;
            geom = BakedGeom::Reversed;
        } else {
            // Only now is the polyline actually needed. Deciding the flag
            // first matters more than it looks: this is the only random read
            // in the writer, so every edge that defers to its twin is one
            // seek saved. Shared mapping, no lock: `read_shared` slices the
            // same pages the serial loop faulted.
            chains::PointReader::read_shared(points, e.pts_start, e.pts_len, scratch)?;
            if e.chain_rev {
                scratch.reverse();
            }
            assert!(
                geom::fits(scratch),
                "chain of {} points cannot be encoded; the collapse should \
                 have split it",
                scratch.len()
            );
            // The blob holds only the interior, so the reader rebuilds
            // both ends out of `nodes.bin`.
            assert_endpoints(scratch, node_coords, e.source, e.target, e.chain);
            encoded.clear();
            let n = geom::encode(scratch, encoded);
            debug_assert_eq!(n as usize, geom::encoded_points(scratch) as usize);
            geom = BakedGeom::Blob(std::mem::take(encoded));
        }
    }
    let lanes = if e.lane_count > 0 && e.lane_off != NO_LANES {
        let start = e.lane_off as usize;
        lane_pool[start..start + e.lane_count as usize].to_vec()
    } else {
        Vec::new()
    };
    Ok(BakedEdge {
        cen_source: e.source,
        cen_target: e.target,
        cen_dist: e.dist_mm,
        cen_named: e.name_offset != NO_NAME,
        delta,
        dist,
        escaped,
        type_,
        speed_limit: e.speed_limit,
        name_offset: e.name_offset,
        geom,
        lanes,
    })
}

/// Stage one baked edge's record: the narrowing replayed verbatim.
///
/// `EdgeFile::push` narrows from the live edge; this narrows from the baked
/// decision. Same predicate, same rows, same bitmap updates — the function
/// exists so the two cannot drift: any change to narrowing updates both
/// call sites' shared outcome type... (they share `BakedEdge`'s fields, and
/// this function is the drain's only writer into `edges_out`).
fn push_baked_edge(edges_out: &mut EdgeFile, item: &BakedEdge) -> Result<()> {
    let (delta, dist) = match item.escaped {
        Some((target, dist)) => {
            let idx = edges_out.pushed as u32;
            edges_out.escapes.push((idx, target, dist));
            (TARGET_DELTA_ESCAPE, DIST_MM_ESCAPE)
        }
        None => (item.delta, item.dist),
    };
    let mut rec = [0u8; EDGE_REC_BYTES as usize];
    rec[0..2].copy_from_slice(&delta.to_le_bytes());
    rec[2..5].copy_from_slice(&dist.to_le_bytes()[..3]);
    rec[5] = item.type_;
    rec[6] = item.speed_limit;
    edges_out
        .blocked
        .push(&rec)
        .map_err(|e| Error(format!("cannot stage edge record: {e}")))?;
    if item.name_offset != NO_NAME {
        edges_out.named.set(edges_out.pushed);
        edges_out.name_offsets.push(item.name_offset);
    }
    edges_out.pushed += 1;
    Ok(())
}

