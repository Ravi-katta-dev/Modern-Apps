use tilecodec::mamaps::body::{
    serialize_into_v10, Body, Feature, Layer, Part, Scratch, DEFAULT_EXTENT, GEOM_LINE,
    GEOM_POLYGON, NAME_NONE, WINDING_OUTER,
};
use tilecodec::mamaps::dict::LAYER_LANDTYPE;

fn landtype_body(named: &[bool]) -> Body {
    let mut layer = Layer::new(LAYER_LANDTYPE);
    for (index, named) in named.iter().copied().enumerate() {
        layer.features.push(Feature {
            kind: if named { 64 } else { 66 },
            kind_detail: 0,
            geom_type: if named { GEOM_LINE } else { GEOM_POLYGON },
            flags: 0,
            name_idx: if named { 1 } else { NAME_NONE },
            parts_offset: index as u32,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        layer.parts.push(Part {
            coord_start: layer.coords.len() as u32,
            point_count: 4,
            winding: WINDING_OUTER,
        });
        layer.coords.extend_from_slice(&[(0, 0), (10, 0), (10, 10), (0, 10)]);
    }
    Body {
        extent: DEFAULT_EXTENT,
        layers: vec![layer],
        names: vec!["Duna".to_string()],
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: Vec::new(),
        convention: None,
        region_links: Vec::new(),
    }
}

#[test]
fn v10_mixed_landtype_round_trips_named_lines_between_polygons() {
    for named in [vec![false, true, false], vec![true, false, true], vec![true], vec![false, false]]
    {
        let body = landtype_body(&named);
        let mut scratch = Scratch::default();
        let bytes = serialize_into_v10(&body, &mut scratch).expect("serialize mixed landtype");
        assert_eq!(bytes.get(3), Some(&10));
        assert_eq!(Body::parse(bytes).expect("parse mixed landtype"), body);
    }
}

#[test]
fn v10_wide_landtype_writes_its_lane_byte_before_the_next_tag() {
    let body = landtype_body(&[false, true, false]);
    let mut scratch = Scratch::default();
    let bytes = serialize_into_v10(&body, &mut scratch).expect("serialize");
    // One body header plus one layer index. Each Min has tag + 12 bytes;
    // each Road has tag + 17 bytes, including its final zero lane count.
    let payload = 16 + 12;
    assert_eq!(bytes.get(payload), Some(&0));
    assert_eq!(bytes.get(payload + 13), Some(&1));
    assert_eq!(bytes.get(payload + 13 + 17), Some(&0), "wide record lane byte");
    assert_eq!(bytes.get(payload + 13 + 18), Some(&0), "next Min tag");
}

#[test]
fn v10_mixed_landtype_still_rejects_invalid_class_tags() {
    let body = landtype_body(&[false, true]);
    let mut scratch = Scratch::default();
    let mut bytes = serialize_into_v10(&body, &mut scratch).expect("serialize").to_vec();
    *bytes.get_mut(16 + 12).expect("first class tag") = 2;
    let error = Body::parse(&bytes).expect_err("unknown class tag");
    assert!(error.to_string().contains("class tag 2"));
}
