impl<'a> Builder<'a> {
    /// [`Builder::conv_int8`] with resolved weight offsets rather than table indices.
    ///
    /// As [`Builder::conv_raw`]: the section parser validated shapes, so lowering only
    /// translates addressing. `m` is the section's computed output channels.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_int8_raw(
        &mut self,
        input: Id,
        weight: u32,
        scale: u32,
        bias: u32,
        m: u32,
        act: Act,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        quant: Quant,
    ) -> Id {
        self.push_conv_int8(
            input, weight, scale, bias, m, kernel, stride, dilation, pads, group, act,
            quant,
        )
    }

    /// Resolve [`Act::PRelu`]'s slope tensor, which is `[channels, 1, 1]` in the ONNX
    /// exports this reads.
    fn act_weight(&mut self, act: Act, channels: u32) -> u32 {
        match act {
            Act::PRelu(index) => self.weight(index, &[channels, 1, 1]),
            _ => 0,
        }
    }

    /// The common case in both nets: `3x3` or `1x1`, stride 1, `pad == dilation`
    /// (which keeps the output size), one group.
    pub fn conv_same(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: u32,
        dilation: u32,
        act: Act,
    ) -> Id {
        // `pad == dilation` holds the spatial size only for an odd kernel, which is
        // the only case either net uses it for.
        let pad = if kernel == 1 { 0 } else { dilation };
        self.conv(
            input,
            weight_index,
            m,
            (kernel, kernel),
            (1, 1),
            (dilation, dilation),
            (pad, pad, pad, pad),
            1,
            act,
        )
    }
    /// A transposed convolution: weights `[in_c, m/group, kh, kw]`, output
    /// `(in - 1) * stride + dilation * (k - 1) + 1 - pads`.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_transpose(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        pads: (u32, u32, u32, u32),
        act: Act,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let weight = self.weight(weight_index, &[in_shape.c, m, kh, kw]);
        let bias = self.weight(weight_index + 1, &[m]);
        let (pad_t, pad_l, pad_b, pad_r) = pads;
        let out_h = deconv_out(in_shape.h, kh, stride.0, pad_t + pad_b);
        let out_w = deconv_out(in_shape.w, kw, stride.1, pad_l + pad_r);
        let out = self.tensor(Shape::new(m, out_h, out_w));
        let act_weight = self.act_weight(act, m);
        self.nodes.push(Node::Conv {
            input,
            out,
            weight,
            bias,
            kernel,
            stride,
            dilation: (1, 1),
            pad: (pad_t, pad_l),
            group: 1,
            act,
            act_weight,
            transpose: true,
            pad_edge: false,
            res: None,
            shift: None,
        });
        out
    }
}
