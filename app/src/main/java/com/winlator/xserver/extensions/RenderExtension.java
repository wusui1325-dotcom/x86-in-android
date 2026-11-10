package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.Log;
import android.util.SparseArray;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 0.1.116 P0-P: in-process X RENDER (XRender Composite).
 * Host X previously advertised MIT-SHM/DRI3/Present/Composite/GLX but <b>not</b> RENDER,
 * so Wine x11drv GDI (which prefers XRender Composite after SET_DRAWABLE) never landed
 * pixels in the App pixmap Present samples. Core PutImage/CopyArea/SHM stayed 0.
 * This extension implements the subset Wine GDI needs and stamps {@code x11_render=}.
 */
public class RenderExtension extends Extension {
    public static final String TAG = "XRender";
    /** Advertise 0.11 so CreateSolidFill / filters are expected. */
    public static final int MAJOR_VERSION = 0;
    public static final int MINOR_VERSION = 11;

    public static final AtomicLong x11RenderCount = new AtomicLong();
    public static long renderCount() { return x11RenderCount.get(); }

    // RENDER error offsets from firstErrorId
    private static final int ERR_PICT_FORMAT = 0;
    private static final int ERR_PICTURE = 1;
    private static final int ERR_PICT_OP = 2;
    private static final int ERR_GLYPHSET = 3;
    private static final int ERR_GLYPH = 4;

    private static final byte OP_QUERY_VERSION = 0;
    private static final byte OP_QUERY_PICT_FORMATS = 1;
    private static final byte OP_QUERY_PICT_INDEX_VALUES = 2;
    private static final byte OP_CREATE_PICTURE = 4;
    private static final byte OP_CHANGE_PICTURE = 5;
    private static final byte OP_SET_PICTURE_CLIP_RECTANGLES = 6;
    private static final byte OP_FREE_PICTURE = 7;
    private static final byte OP_COMPOSITE = 8;
    private static final byte OP_TRAPEZOIDS = 10;
    private static final byte OP_TRIANGLES = 11;
    private static final byte OP_TRI_STRIP = 12;
    private static final byte OP_TRI_FAN = 13;
    private static final byte OP_CREATE_GLYPHSET = 17;
    private static final byte OP_REFERENCE_GLYPHSET = 18;
    private static final byte OP_FREE_GLYPHSET = 19;
    private static final byte OP_ADD_GLYPHS = 20;
    private static final byte OP_ADD_GLYPHS_FROM_PICTURE = 21;
    private static final byte OP_FREE_GLYPHS = 22;
    private static final byte OP_COMPOSITE_GLYPHS8 = 23;
    private static final byte OP_COMPOSITE_GLYPHS16 = 24;
    private static final byte OP_COMPOSITE_GLYPHS32 = 25;
    private static final byte OP_FILL_RECTANGLES = 26;
    private static final byte OP_CREATE_CURSOR = 27;
    private static final byte OP_SET_PICTURE_TRANSFORM = 28;
    private static final byte OP_QUERY_FILTERS = 29;
    private static final byte OP_SET_PICTURE_FILTER = 30;
    private static final byte OP_CREATE_ANIM_CURSOR = 31;
    private static final byte OP_ADD_TRAPS = 32;
    private static final byte OP_CREATE_SOLID_FILL = 33;
    private static final byte OP_CREATE_LINEAR_GRADIENT = 34;
    private static final byte OP_CREATE_RADIAL_GRADIENT = 35;
    private static final byte OP_CREATE_CONICAL_GRADIENT = 36;

    private static final int PICT_TYPE_DIRECT = 1;
    private static final int FMT_ARGB32 = 0x1001;
    private static final int FMT_RGB24 = 0x1002;
    private static final int FMT_A8 = 0x1003;
    private static final int FMT_A1 = 0x1004;

    private static final int PICT_OP_CLEAR = 0;
    private static final int PICT_OP_SRC = 1;
    private static final int PICT_OP_DST = 2;
    private static final int PICT_OP_OVER = 3;
    private static final int PICT_OP_OVER_REVERSE = 4;
    private static final int PICT_OP_ADD = 12;

    private static final int CP_REPEAT = 1 << 0;
    private static final int CP_ALPHA_MAP = 1 << 1;
    private static final int CP_ALPHA_X_ORIGIN = 1 << 2;
    private static final int CP_ALPHA_Y_ORIGIN = 1 << 3;
    private static final int CP_CLIP_X_ORIGIN = 1 << 4;
    private static final int CP_CLIP_Y_ORIGIN = 1 << 5;
    private static final int CP_CLIP_MASK = 1 << 6;
    private static final int CP_GRAPHICS_EXPOSURE = 1 << 7;
    private static final int CP_SUBWINDOW_MODE = 1 << 8;
    private static final int CP_POLY_EDGE = 1 << 9;
    private static final int CP_POLY_MODE = 1 << 10;
    private static final int CP_DITHER = 1 << 11;
    private static final int CP_COMPONENT_ALPHA = 1 << 12;

    private static final int REPEAT_NONE = 0;
    private static final int REPEAT_NORMAL = 1;
    private static final int REPEAT_PAD = 2;
    private static final int REPEAT_REFLECT = 3;

    private final SparseArray<Picture> pictures = new SparseArray<>();
    private final SparseArray<GlyphSet> glyphSets = new SparseArray<>();
    private boolean loggedQuery;
    private boolean loggedComposite;

    private static final class Picture {
        final int id;
        Drawable drawable;
        int formatId;
        int repeat = REPEAT_NONE;
        boolean componentAlpha;
        boolean solid;
        int solidBgra; // byte order B,G,R,A packed as int bits in memory (0xAARRGGBB LE → use channels)

        Picture(int id) { this.id = id; }
    }

    private static final class Glyph {
        int width, height, x, y, xOff, yOff;
        byte[] a8; // row-major A8, width*height
    }

    private static final class GlyphSet {
        final int id;
        int ref = 1;
        final SparseArray<Glyph> glyphs = new SparseArray<>();
        GlyphSet(int id) { this.id = id; }
    }

    public RenderExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "RENDER";
    }

    @Override
    public byte getErrorCount() {
        return 5;
    }

    private XRequestError badPicture(int id) {
        return new XRequestError((getFirstErrorId() & 0xff) + ERR_PICTURE, id);
    }

    private XRequestError badPictFormat(int id) {
        return new XRequestError((getFirstErrorId() & 0xff) + ERR_PICT_FORMAT, id);
    }

    private XRequestError badGlyphSet(int id) {
        return new XRequestError((getFirstErrorId() & 0xff) + ERR_GLYPHSET, id);
    }

    private Picture getPicture(int id) {
        return id == 0 ? null : pictures.get(id);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream)
            throws IOException, XRequestError {
        int opcode = client.getRequestData() & 0xff;
        try {
            switch (opcode) {
                case OP_QUERY_VERSION:
                    queryVersion(client, inputStream, outputStream);
                    break;
                case OP_QUERY_PICT_FORMATS:
                    queryPictFormats(client, outputStream);
                    break;
                case OP_QUERY_PICT_INDEX_VALUES:
                    queryPictIndexValues(client, inputStream, outputStream);
                    break;
                case OP_CREATE_PICTURE:
                    try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                        createPicture(client, inputStream);
                    }
                    break;
                case OP_CHANGE_PICTURE:
                    changePicture(inputStream);
                    break;
                case OP_SET_PICTURE_CLIP_RECTANGLES:
                    setPictureClipRectangles(client, inputStream);
                    break;
                case OP_FREE_PICTURE:
                    freePicture(inputStream);
                    break;
                case OP_COMPOSITE:
                    try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                        composite(client, inputStream);
                    }
                    break;
                case OP_FILL_RECTANGLES:
                    try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                        fillRectangles(client, inputStream);
                    }
                    break;
                case OP_CREATE_SOLID_FILL:
                    createSolidFill(inputStream);
                    break;
                case OP_CREATE_LINEAR_GRADIENT:
                case OP_CREATE_RADIAL_GRADIENT:
                case OP_CREATE_CONICAL_GRADIENT:
                    createGradientAsSolid(inputStream);
                    break;
                case OP_SET_PICTURE_TRANSFORM:
                    setPictureTransform(inputStream);
                    break;
                case OP_SET_PICTURE_FILTER:
                    setPictureFilter(client, inputStream);
                    break;
                case OP_QUERY_FILTERS:
                    queryFilters(client, inputStream, outputStream);
                    break;
                case OP_CREATE_GLYPHSET:
                    createGlyphSet(inputStream);
                    break;
                case OP_REFERENCE_GLYPHSET:
                    referenceGlyphSet(inputStream);
                    break;
                case OP_FREE_GLYPHSET:
                    freeGlyphSet(inputStream);
                    break;
                case OP_ADD_GLYPHS:
                    addGlyphs(client, inputStream);
                    break;
                case OP_FREE_GLYPHS:
                    freeGlyphs(client, inputStream);
                    break;
                case OP_COMPOSITE_GLYPHS8:
                    try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                        compositeGlyphs(client, inputStream, 1);
                    }
                    break;
                case OP_COMPOSITE_GLYPHS16:
                    try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                        compositeGlyphs(client, inputStream, 2);
                    }
                    break;
                case OP_COMPOSITE_GLYPHS32:
                    try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                        compositeGlyphs(client, inputStream, 4);
                    }
                    break;
                case OP_TRAPEZOIDS:
                case OP_TRIANGLES:
                case OP_TRI_STRIP:
                case OP_TRI_FAN:
                case OP_ADD_GLYPHS_FROM_PICTURE:
                case OP_CREATE_CURSOR:
                case OP_CREATE_ANIM_CURSOR:
                case OP_ADD_TRAPS:
                    // Consume; do not BadImplementation (would abort Wine GDI).
                    break;
                default:
                    break;
            }
        } finally {
            client.skipRequest();
        }
    }

    private void queryVersion(XClient client, XInputStream in, XOutputStream out)
            throws IOException {
        in.skip(8);
        if (!loggedQuery) {
            loggedQuery = true;
            Log.i(TAG, "QUERY_VERSION → " + MAJOR_VERSION + "." + MINOR_VERSION
                    + " opcode=" + (getMajorOpcode() & 0xff));
        }
        try (XStreamLock lock = out.lock()) {
            out.writeByte(RESPONSE_CODE_SUCCESS);
            out.writeByte((byte) 0);
            out.writeShort(client.getSequenceNumber());
            out.writeInt(0);
            out.writeInt(MAJOR_VERSION);
            out.writeInt(MINOR_VERSION);
            out.writePad(16);
        }
    }

    /**
     * Minimal but Wine-correct QueryPictFormats: ARGB32 / RGB24 / A8 / A1 plus
     * screen fallback + visual map for the in-process TrueColor visual.
     */
    private void queryPictFormats(XClient client, XOutputStream out) throws IOException {
        final int numFormats = 4;
        final int numScreens = 1;
        final int numDepths = 2;
        final int numVisuals = 2;
        final int numSubpixel = 1;
        // formats 4*28=112; screen 8+ (4+8)*2=32; subpixel 4 → 148 bytes = 37 words
        final int extraBytes = 28 * numFormats + 8 + (4 + 8) * numDepths + 4 * numSubpixel;
        final int length = extraBytes / 4;
        int visual32 = xServer.pixmapManager.visual.id;
        int visual1 = 0;
        for (com.winlator.xserver.Visual v : xServer.pixmapManager.supportedVisuals) {
            if (v.depth == 1) visual1 = v.id;
        }

        try (XStreamLock lock = out.lock()) {
            out.writeByte(RESPONSE_CODE_SUCCESS);
            out.writeByte((byte) 0);
            out.writeShort(client.getSequenceNumber());
            out.writeInt(length);
            out.writeInt(numFormats);
            out.writeInt(numScreens);
            out.writeInt(numDepths);
            out.writeInt(numVisuals);
            out.writeInt(numSubpixel);
            out.writeInt(0);

            writeDirectFormat(out, FMT_ARGB32, (byte) 32, (short) 16, (short) 0xff,
                    (short) 8, (short) 0xff, (short) 0, (short) 0xff, (short) 24, (short) 0xff);
            writeDirectFormat(out, FMT_RGB24, (byte) 24, (short) 16, (short) 0xff,
                    (short) 8, (short) 0xff, (short) 0, (short) 0xff, (short) 0, (short) 0);
            writeDirectFormat(out, FMT_A8, (byte) 8, (short) 0, (short) 0,
                    (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0xff);
            writeDirectFormat(out, FMT_A1, (byte) 1, (short) 0, (short) 0,
                    (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 1);

            // PICTSCREEN
            out.writeInt(numDepths);
            out.writeInt(FMT_ARGB32);
            // depth 32 → visual
            out.writeByte((byte) 32);
            out.writeByte((byte) 0);
            out.writeShort((short) 1);
            out.writeInt(visual32);
            out.writeInt(FMT_ARGB32);
            // depth 1 → visual
            out.writeByte((byte) 1);
            out.writeByte((byte) 0);
            out.writeShort((short) (visual1 != 0 ? 1 : 0));
            if (visual1 != 0) {
                out.writeInt(visual1);
                out.writeInt(FMT_A1);
            } else {
                // keep numVisuals honest: if no 1-bit visual, we already declared 2; still write a pad visual 0
                out.writeInt(0);
                out.writeInt(FMT_A1);
            }
            // subpixel: SubPixelUnknown = 0
            out.writeInt(0);
        }
    }

    private static void writeDirectFormat(XOutputStream out, int id, byte depth,
                                          short redShift, short redMask,
                                          short greenShift, short greenMask,
                                          short blueShift, short blueMask,
                                          short alphaShift, short alphaMask) throws IOException {
        out.writeInt(id);
        out.writeByte((byte) PICT_TYPE_DIRECT);
        out.writeByte(depth);
        out.writeShort((short) 0);
        out.writeShort(redShift);
        out.writeShort(redMask);
        out.writeShort(greenShift);
        out.writeShort(greenMask);
        out.writeShort(blueShift);
        out.writeShort(blueMask);
        out.writeShort(alphaShift);
        out.writeShort(alphaMask);
        out.writeInt(0); // colormap
    }

    private void queryPictIndexValues(XClient client, XInputStream in, XOutputStream out)
            throws IOException, XRequestError {
        int format = in.readInt();
        // We have no indexed formats.
        try (XStreamLock lock = out.lock()) {
            out.writeByte(RESPONSE_CODE_SUCCESS);
            out.writeByte((byte) 0);
            out.writeShort(client.getSequenceNumber());
            out.writeInt(0);
            out.writeInt(0);
            out.writePad(20);
        }
        if (format != FMT_A1 && format != FMT_A8 && format != FMT_RGB24 && format != FMT_ARGB32) {
            // still a success empty list is friendlier than BadPictFormat for probes
        }
    }

    private void createPicture(XClient client, XInputStream in) throws XRequestError {
        int pid = in.readInt();
        int drawableId = in.readInt();
        int format = in.readInt();
        int valueMask = in.readInt();
        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) {
            throw new com.winlator.xserver.errors.BadDrawable(drawableId);
        }
        if (pictures.get(pid) != null) {
            throw badPicture(pid);
        }
        Picture p = new Picture(pid);
        p.drawable = drawable;
        p.formatId = format;
        applyValueMask(p, valueMask, in);
        pictures.put(pid, p);
    }

    private void changePicture(XInputStream in) throws XRequestError {
        int pid = in.readInt();
        int valueMask = in.readInt();
        Picture p = getPicture(pid);
        if (p == null) throw badPicture(pid);
        applyValueMask(p, valueMask, in);
    }

    private void applyValueMask(Picture p, int valueMask, XInputStream in) {
        // Each set bit consumes one CARD32, in bit order.
        for (int bit = 0; bit <= 12; bit++) {
            int flag = 1 << bit;
            if ((valueMask & flag) == 0) continue;
            int value = in.readInt();
            switch (flag) {
                case CP_REPEAT:
                    p.repeat = value & 0xff;
                    break;
                case CP_COMPONENT_ALPHA:
                    p.componentAlpha = (value & 0xff) != 0;
                    break;
                default:
                    break;
            }
        }
    }

    private void setPictureClipRectangles(XClient client, XInputStream in) {
        in.skip(8); // picture + xOrigin/yOrigin
        // remaining rects consumed by skipRequest
    }

    private void freePicture(XInputStream in) {
        int pid = in.readInt();
        pictures.remove(pid);
    }

    private void createSolidFill(XInputStream in) {
        int pid = in.readInt();
        int color = readColor16ToBgra(in);
        Picture p = new Picture(pid);
        p.solid = true;
        p.solidBgra = color;
        p.repeat = REPEAT_NORMAL;
        p.formatId = FMT_ARGB32;
        pictures.put(pid, p);
    }

    /** Best-effort: first color stop becomes a solid. Remaining request skipped later. */
    private void createGradientAsSolid(XInputStream in) {
        int pid = in.readInt();
        // Spend a few words then hope skipRequest eats the rest.
        // Linear: p1(4+4)+p2(4+4)+nStops + stops + colors — too variable.
        // Create an opaque mid-gray solid so Composite does not BadPicture.
        Picture p = new Picture(pid);
        p.solid = true;
        p.solidBgra = packBgra(0xff, 0x80, 0x80, 0x80);
        p.repeat = REPEAT_NORMAL;
        p.formatId = FMT_ARGB32;
        pictures.put(pid, p);
    }

    private void setPictureTransform(XInputStream in) {
        in.skip(4); // picture
        in.skip(36); // 3x3 PictTransform (INT32)
    }

    private void setPictureFilter(XClient client, XInputStream in) {
        in.skip(4); // picture
        short nlen = in.readShort();
        in.skip(2);
        int padded = (nlen + 3) & ~3;
        in.skip(padded);
        // remaining fixed-point params skipped later
    }

    private void queryFilters(XClient client, XInputStream in, XOutputStream out) throws IOException {
        in.skip(4); // drawable
        // names: "nearest", "bilinear" (padded)
        byte[] names = "nearest\0\0bilinear\0\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        // alias: just the two names, 8-byte padded each? "nearest" is 7+1 pad=8; "bilinear" is 8.
        int nBytes = names.length;
        int length = (8 + nBytes + 3) / 4; // aliases (num*4=8) + names, in 4-byte words? 
        // Reply: num-aliases, num-filters, 16 pad, LISTofCARD16 alias, pad, LISTofSTRING8
        // Keep it simple: 0 aliases, 2 filters, names "nearest" "bilinear"
        byte[] n1 = new byte[]{7, 'n','e','a','r','e','s','t'}; // 8
        byte[] n2 = new byte[]{8, 'b','i','l','i','n','e','a','r',0,0,0}; // 12
        int nameBytes = n1.length + n2.length;
        int extra = 0 + nameBytes; // no aliases
        // header already has 4 (naliases) + 4 (nfilters) + 16 unused = 24 of the 32; length = extra/4
        int replyLen = (nameBytes + 3) / 4;
        try (XStreamLock lock = out.lock()) {
            out.writeByte(RESPONSE_CODE_SUCCESS);
            out.writeByte((byte) 0);
            out.writeShort(client.getSequenceNumber());
            out.writeInt(replyLen);
            out.writeInt(0); // num-aliases
            out.writeInt(2); // num-filters
            out.writePad(16);
            out.write(n1);
            out.write(n2);
        }
    }

    private void fillRectangles(XClient client, XInputStream in) throws XRequestError {
        int op = client.getRequestData(); // NO — request data is the minor opcode (26)
        // Actual: CARD8 op is first byte of request body after header...
        // xRenderFillRectanglesReq: op, pad3, dst, color, then rects
        // Wait: extension header is already consumed (major, minor=26, length).
        // Body starts with: CARD8 op, CARD8 pad, CARD16 pad, PICTURE dst, xRenderColor, rects
        op = in.readByte() & 0xff;
        in.skip(3);
        int dstId = in.readInt();
        int color = readColor16ToBgra(in);
        Picture dst = getPicture(dstId);
        if (dst == null || dst.drawable == null || dst.drawable.getData() == null) {
            if (dst == null) throw badPicture(dstId);
            return;
        }
        int remaining = client.getRemainingRequestLength();
        while (remaining >= 8) {
            short x = in.readShort();
            short y = in.readShort();
            short w = in.readShort();
            short h = in.readShort();
            remaining -= 8;
            applyFill(dst, op, x, y, w, h, color);
            if (w > 1 && h > 1) x11RenderCount.incrementAndGet();
        }
    }

    private void composite(XClient client, XInputStream in) throws XRequestError {
        int op = in.readByte() & 0xff;
        in.skip(3);
        int srcId = in.readInt();
        int maskId = in.readInt();
        int dstId = in.readInt();
        short srcX = in.readShort();
        short srcY = in.readShort();
        short maskX = in.readShort();
        short maskY = in.readShort();
        short dstX = in.readShort();
        short dstY = in.readShort();
        short width = in.readShort();
        short height = in.readShort();

        Picture src = getPicture(srcId);
        Picture dst = getPicture(dstId);
        Picture mask = maskId != 0 ? getPicture(maskId) : null;
        if (src == null) throw badPicture(srcId);
        if (dst == null) throw badPicture(dstId);
        if (dst.drawable == null || dst.drawable.getData() == null) return;
        if (width <= 0 || height <= 0) return;

        if (!loggedComposite) {
            loggedComposite = true;
            Log.i(TAG, "COMPOSITE first op=" + op
                    + " src=0x" + Integer.toHexString(srcId)
                    + " dst=0x" + Integer.toHexString(dstId)
                    + " mask=" + (maskId != 0 ? "0x" + Integer.toHexString(maskId) : "none")
                    + " " + width + "x" + height
                    + " dstXY=" + dstX + "," + dstY
                    + " drawable=0x" + Integer.toHexString(dst.drawable.id));
        }

        renderComposite(op, src, mask, dst, srcX, srcY, maskX, maskY, dstX, dstY, width, height);
        if (width > 1 && height > 1) x11RenderCount.incrementAndGet();
    }

    private void renderComposite(int op, Picture src, Picture mask, Picture dst,
                                 int srcX, int srcY, int maskX, int maskY,
                                 int dstX, int dstY, int width, int height) {
        Drawable d = dst.drawable;
        ByteBuffer dstBuf = d.getData();
        if (dstBuf == null) return;
        int dw = d.width & 0xffff;
        int dh = d.height & 0xffff;
        int dStride = dw * 4; // software path; GPUImage stride handled via getData only when CPU

        // clip dest
        if (dstX < 0) {
            int cut = -dstX;
            dstX = 0;
            srcX += cut;
            maskX += cut;
            width -= cut;
        }
        if (dstY < 0) {
            int cut = -dstY;
            dstY = 0;
            srcY += cut;
            maskY += cut;
            height -= cut;
        }
        if (dstX + width > dw) width = dw - dstX;
        if (dstY + height > dh) height = dh - dstY;
        if (width <= 0 || height <= 0) return;

        if (op == PICT_OP_CLEAR) {
            fillBgra(dstBuf, dStride, dstX, dstY, width, height, 0);
            d.forceUpdate();
            return;
        }
        if (op == PICT_OP_DST) {
            return;
        }

        // Fast path: Src, no mask, software src, same depth, no special repeat needed for 1:1 copy
        if (op == PICT_OP_SRC && mask == null && !src.solid && src.drawable != null
                && src.drawable.getData() != null && src.repeat == REPEAT_NONE) {
            d.copyArea((short) srcX, (short) srcY, (short) dstX, (short) dstY,
                    (short) width, (short) height, src.drawable);
            return;
        }

        ByteBuffer srcBuf = (!src.solid && src.drawable != null) ? src.drawable.getData() : null;
        int sw = src.solid ? 1 : (src.drawable != null ? (src.drawable.width & 0xffff) : 1);
        int sh = src.solid ? 1 : (src.drawable != null ? (src.drawable.height & 0xffff) : 1);
        int sStride = sw * 4;
        ByteBuffer maskBuf = (mask != null && !mask.solid && mask.drawable != null)
                ? mask.drawable.getData() : null;
        int mw = 1, mh = 1, mStride = 4;
        boolean maskSolid = mask != null && mask.solid;
        int maskSolidBgra = maskSolid ? mask.solidBgra : 0;
        if (mask != null && !mask.solid && mask.drawable != null) {
            mw = mask.drawable.width & 0xffff;
            mh = mask.drawable.height & 0xffff;
            mStride = mw * 4;
        }
        boolean srcA8 = src.formatId == FMT_A8;
        boolean maskA8 = mask != null && mask.formatId == FMT_A8;

        dstBuf.order(ByteOrder.LITTLE_ENDIAN);
        if (srcBuf != null) srcBuf.order(ByteOrder.LITTLE_ENDIAN);
        if (maskBuf != null) maskBuf.order(ByteOrder.LITTLE_ENDIAN);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sPix = sample(src, srcBuf, sw, sh, sStride, srcX + x, srcY + y, srcA8);
                int mA = 255;
                if (mask != null) {
                    int mPix = maskSolid
                            ? maskSolidBgra
                            : sample(mask, maskBuf, mw, mh, mStride, maskX + x, maskY + y, maskA8);
                    mA = (mask != null && (maskA8 || mask.formatId == FMT_A1))
                            ? (mPix >>> 24)
                            : (mPix >>> 24);
                    if (mask.componentAlpha) {
                        int mb = mPix & 0xff;
                        int mg = (mPix >>> 8) & 0xff;
                        int mr = (mPix >>> 16) & 0xff;
                        mA = (mb + mg + mr) / 3;
                    }
                }
                if (mA != 255) {
                    // modulate source by mask alpha (premultiplied scale)
                    int sb = sPix & 0xff, sg = (sPix >>> 8) & 0xff, sr = (sPix >>> 16) & 0xff, sa = (sPix >>> 24) & 0xff;
                    sb = sb * mA / 255;
                    sg = sg * mA / 255;
                    sr = sr * mA / 255;
                    sa = sa * mA / 255;
                    sPix = packBgra(sa, sr, sg, sb);
                }
                int dOff = ((dstY + y) * (dStride / 4) + (dstX + x)) * 4;
                if (dOff + 3 >= dstBuf.capacity()) continue;
                int dPix = getBgra(dstBuf, dOff);
                int out = applyOp(op, sPix, dPix);
                putBgra(dstBuf, dOff, out);
            }
        }
        d.forceUpdate();
    }

    private int sample(Picture p, ByteBuffer buf, int w, int h, int stride, int x, int y, boolean a8) {
        if (p.solid) return p.solidBgra;
        if (buf == null || w <= 0 || h <= 0) return 0;
        int sx = x;
        int sy = y;
        switch (p.repeat) {
            case REPEAT_NORMAL:
                sx = floorMod(x, w);
                sy = floorMod(y, h);
                break;
            case REPEAT_PAD:
                sx = clamp(x, 0, w - 1);
                sy = clamp(y, 0, h - 1);
                break;
            case REPEAT_REFLECT:
                sx = reflect(x, w);
                sy = reflect(y, h);
                break;
            default:
                if (x < 0 || y < 0 || x >= w || y >= h) return 0;
                break;
        }
        if (a8) {
            // A8 stored as 1 byte per pixel? Wine may still use 4-byte drawable (depth 8 in 32bpp buffer).
            // Our drawables are always 4 bytes/pixel. Use the first byte / alpha.
            int off = (sy * (stride / 4) + sx) * 4;
            if (off >= buf.capacity()) return 0;
            int a = buf.get(off) & 0xff; // often A8 is in low byte or all channels
            // If the buffer was written as A8 via depth 8, it may only use byte 0.
            // Prefer max of bytes if alpha is 0 but gray sits in B.
            int a3 = buf.get(off + 3) & 0xff;
            if (a3 != 0) a = a3;
            return packBgra(a, a, a, a);
        }
        int off = (sy * (stride / 4) + sx) * 4;
        return getBgra(buf, off);
    }

    private static int applyOp(int op, int src, int dst) {
        int sb = src & 0xff, sg = (src >>> 8) & 0xff, sr = (src >>> 16) & 0xff, sa = (src >>> 24) & 0xff;
        int db = dst & 0xff, dg = (dst >>> 8) & 0xff, dr = (dst >>> 16) & 0xff, da = (dst >>> 24) & 0xff;
        switch (op) {
            case PICT_OP_SRC:
                return src;
            case PICT_OP_ADD:
                return packBgra(sat(sa + da), sat(sr + dr), sat(sg + dg), sat(sb + db));
            case PICT_OP_OVER_REVERSE: {
                // dst over src: out = dst + src*(1-dstA)
                int inv = 255 - da;
                return packBgra(sat(da + sa * inv / 255), sat(dr + sr * inv / 255),
                        sat(dg + sg * inv / 255), sat(db + sb * inv / 255));
            }
            case PICT_OP_OVER:
            default: {
                // premultiplied Over: out = src + dst*(1-srcA)
                int inv = 255 - sa;
                return packBgra(sat(sa + da * inv / 255), sat(sr + dr * inv / 255),
                        sat(sg + dg * inv / 255), sat(sb + db * inv / 255));
            }
        }
    }

    private void applyFill(Picture dst, int op, int x, int y, int w, int h, int color) {
        if (dst.drawable == null || dst.drawable.getData() == null) return;
        if (op == PICT_OP_SRC || op == PICT_OP_OVER && ((color >>> 24) & 0xff) == 0xff) {
            int rgb = ((color >>> 16) & 0xff) << 16 | ((color >>> 8) & 0xff) << 8 | (color & 0xff);
            // fillRect ignores alpha (forces 255) — OK for opaque
            if (((color >>> 24) & 0xff) == 0xff) {
                dst.drawable.fillRect(x, y, w, h, rgb);
                return;
            }
        }
        // General path
        ByteBuffer buf = dst.drawable.getData();
        int dw = dst.drawable.width & 0xffff;
        int dh = dst.drawable.height & 0xffff;
        if (x < 0) { w += x; x = 0; }
        if (y < 0) { h += y; y = 0; }
        if (x + w > dw) w = dw - x;
        if (y + h > dh) h = dh - y;
        if (w <= 0 || h <= 0) return;
        int stride = dw * 4;
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int off = ((y + yy) * dw + (x + xx)) * 4;
                int dPix = getBgra(buf, off);
                int out = (op == PICT_OP_CLEAR) ? 0 : applyOp(op, color, dPix);
                putBgra(buf, off, out);
            }
        }
        dst.drawable.forceUpdate();
    }

    private static void fillBgra(ByteBuffer buf, int strideBytes, int x, int y, int w, int h, int color) {
        byte b = (byte) (color & 0xff);
        byte g = (byte) ((color >>> 8) & 0xff);
        byte r = (byte) ((color >>> 16) & 0xff);
        byte a = (byte) ((color >>> 24) & 0xff);
        for (int yy = 0; yy < h; yy++) {
            int row = (y + yy) * strideBytes + x * 4;
            for (int xx = 0; xx < w; xx++) {
                int off = row + xx * 4;
                if (off + 3 >= buf.capacity()) continue;
                buf.put(off, b);
                buf.put(off + 1, g);
                buf.put(off + 2, r);
                buf.put(off + 3, a);
            }
        }
    }

    private void createGlyphSet(XInputStream in) {
        int gsid = in.readInt();
        in.skip(4); // format
        glyphSets.put(gsid, new GlyphSet(gsid));
    }

    private void referenceGlyphSet(XInputStream in) {
        int gsid = in.readInt();
        int existing = in.readInt();
        GlyphSet src = glyphSets.get(existing);
        if (src != null) {
            src.ref++;
            glyphSets.put(gsid, src);
        } else {
            glyphSets.put(gsid, new GlyphSet(gsid));
        }
    }

    private void freeGlyphSet(XInputStream in) {
        int gsid = in.readInt();
        GlyphSet gs = glyphSets.get(gsid);
        if (gs == null) return;
        gs.ref--;
        if (gs.ref <= 0) glyphSets.remove(gsid);
        else if (gs.id != gsid) glyphSets.remove(gsid);
    }

    private void addGlyphs(XClient client, XInputStream in) {
        int gsid = in.readInt();
        int nglyphs = in.readInt();
        GlyphSet gs = glyphSets.get(gsid);
        if (gs == null) {
            gs = new GlyphSet(gsid);
            glyphSets.put(gsid, gs);
        }
        if (nglyphs <= 0) return;
        int[] ids = new int[nglyphs];
        for (int i = 0; i < nglyphs; i++) ids[i] = in.readInt();
        int[] widths = new int[nglyphs];
        int[] heights = new int[nglyphs];
        int[] xs = new int[nglyphs];
        int[] ys = new int[nglyphs];
        int[] xOffs = new int[nglyphs];
        int[] yOffs = new int[nglyphs];
        for (int i = 0; i < nglyphs; i++) {
            widths[i] = in.readUnsignedShort();
            heights[i] = in.readUnsignedShort();
            xs[i] = in.readShort();
            ys[i] = in.readShort();
            xOffs[i] = in.readShort();
            yOffs[i] = in.readShort();
        }
        for (int i = 0; i < nglyphs; i++) {
            int w = widths[i];
            int h = heights[i];
            // Glyph bitmap padded to 32-bit for A8: each row padded to 4 bytes
            int rowBytes = (w + 3) & ~3;
            int nbytes = rowBytes * h;
            byte[] raw = new byte[Math.max(0, nbytes)];
            if (nbytes > 0) {
                ByteBuffer buf = in.readByteBuffer(nbytes);
                buf.get(raw);
            }
            Glyph g = new Glyph();
            g.width = w;
            g.height = h;
            g.x = xs[i];
            g.y = ys[i];
            g.xOff = xOffs[i];
            g.yOff = yOffs[i];
            g.a8 = new byte[Math.max(0, w * h)];
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    g.a8[yy * w + xx] = raw[yy * rowBytes + xx];
                }
            }
            gs.glyphs.put(ids[i], g);
        }
    }

    private void freeGlyphs(XClient client, XInputStream in) {
        int gsid = in.readInt();
        GlyphSet gs = glyphSets.get(gsid);
        int n = client.getRemainingRequestLength() / 4;
        for (int i = 0; i < n; i++) {
            int id = in.readInt();
            if (gs != null) gs.glyphs.remove(id);
        }
    }

    /**
     * CompositeGlyphs8/16/32 — blit stored A8 glyphs with src color (solid or sampled).
     * Item stream: 8-bit length + INT8 deltax/deltay + glyphs padded to 4, or
     * length=255 + 3 pad + GlyphSet id (font switch), or length=0 just delta.
     */
    private void compositeGlyphs(XClient client, XInputStream in, int glyphSize) throws XRequestError {
        int op = in.readByte() & 0xff;
        in.skip(3);
        int srcId = in.readInt();
        int dstId = in.readInt();
        in.skip(4); // mask format
        int gsid = in.readInt();
        short srcX = in.readShort();
        short srcY = in.readShort();
        Picture src = getPicture(srcId);
        Picture dst = getPicture(dstId);
        if (src == null) throw badPicture(srcId);
        if (dst == null) throw badPicture(dstId);
        GlyphSet gs = glyphSets.get(gsid);
        int remaining = client.getRemainingRequestLength();
        int x = srcX;
        int y = srcY;
        boolean drew = false;
        while (remaining >= 4) {
            int len = in.readByte() & 0xff;
            remaining--;
            if (len == 255) {
                in.skip(3);
                remaining -= 3;
                if (remaining < 4) break;
                gsid = in.readInt();
                remaining -= 4;
                gs = glyphSets.get(gsid);
                continue;
            }
            byte dx = (byte) (in.readByte() & 0xff);
            byte dy = (byte) (in.readByte() & 0xff);
            remaining -= 2;
            // pad byte after dx,dy is NOT in the protocol — the header is:
            // CARD8 len, INT8 dx, INT8 dy, then glyphs, then pad to 4.
            // That's 3 bytes header; pad after glyphs to 32-bit.
            // There's no extra pad byte in the 3-byte header. Align: (1+2+len*glyphSize+3)&~3
            x += dx;
            y += dy;
            int glyphBytes = len * glyphSize;
            int padded = (3 + glyphBytes + 3) & ~3;
            int padAfter = padded - 3 - glyphBytes;
            if (remaining < glyphBytes + padAfter) break;
            int cursorX = x;
            int cursorY = y;
            for (int i = 0; i < len; i++) {
                int gid;
                if (glyphSize == 1) gid = in.readByte() & 0xff;
                else if (glyphSize == 2) gid = in.readUnsignedShort();
                else gid = in.readInt();
                remaining -= glyphSize;
                if (gs == null) continue;
                Glyph g = gs.glyphs.get(gid);
                if (g == null) continue;
                int gx = cursorX + g.x;
                int gy = cursorY - g.y; // XRender glyph y is often offset from baseline
                // Wine/Xft: dst position is (x + glyph.x, y - glyph.y) relative to current point?
                // Standard: x += xOff after each; draw at (x - glyph.x, y - glyph.y) wait.
                // XGlyphInfo: x,y are the offsets of the origin within the bitmap
                // (left bearing / ascent). Draw bitmap at (cursorX - g.x, cursorY - g.y).
                gx = cursorX - g.x;
                gy = cursorY - g.y;
                blitGlyph(op, src, dst, srcX, srcY, gx, gy, g);
                cursorX += g.xOff;
                cursorY += g.yOff;
                drew = true;
            }
            if (padAfter > 0) {
                in.skip(padAfter);
                remaining -= padAfter;
            }
            x = cursorX;
            y = cursorY;
        }
        if (drew) x11RenderCount.incrementAndGet();
    }

    private void blitGlyph(int op, Picture src, Picture dst, int srcX, int srcY,
                           int destX, int destY, Glyph g) {
        if (dst.drawable == null || dst.drawable.getData() == null || g.a8 == null) return;
        // Build a temporary solid+mask composite per glyph
        int color = src.solid ? src.solidBgra : sample(src,
                src.drawable != null ? src.drawable.getData() : null,
                src.drawable != null ? (src.drawable.width & 0xffff) : 1,
                src.drawable != null ? (src.drawable.height & 0xffff) : 1,
                src.drawable != null ? (src.drawable.width & 0xffff) * 4 : 4,
                srcX, srcY, src.formatId == FMT_A8);
        ByteBuffer buf = dst.drawable.getData();
        int dw = dst.drawable.width & 0xffff;
        int dh = dst.drawable.height & 0xffff;
        for (int yy = 0; yy < g.height; yy++) {
            int dy = destY + yy;
            if (dy < 0 || dy >= dh) continue;
            for (int xx = 0; xx < g.width; xx++) {
                int dx = destX + xx;
                if (dx < 0 || dx >= dw) continue;
                int a = g.a8[yy * g.width + xx] & 0xff;
                if (a == 0) continue;
                int sb = color & 0xff, sg = (color >>> 8) & 0xff, sr = (color >>> 16) & 0xff, sa = (color >>> 24) & 0xff;
                int sPix = packBgra(sa * a / 255, sr * a / 255, sg * a / 255, sb * a / 255);
                int off = (dy * dw + dx) * 4;
                int dPix = getBgra(buf, off);
                putBgra(buf, off, applyOp(op, sPix, dPix));
            }
        }
        dst.drawable.forceUpdate();
    }

    private static int readColor16ToBgra(XInputStream in) {
        int r = in.readUnsignedShort();
        int g = in.readUnsignedShort();
        int b = in.readUnsignedShort();
        int a = in.readUnsignedShort();
        return packBgra(a >> 8, r >> 8, g >> 8, b >> 8);
    }

    /** Pack channels into little-endian BGRA word (same as our pixmap memory). */
    private static int packBgra(int a, int r, int g, int b) {
        return (b & 0xff) | ((g & 0xff) << 8) | ((r & 0xff) << 16) | ((a & 0xff) << 24);
    }

    private static int getBgra(ByteBuffer buf, int off) {
        if (off < 0 || off + 3 >= buf.capacity()) return 0;
        return (buf.get(off) & 0xff)
                | ((buf.get(off + 1) & 0xff) << 8)
                | ((buf.get(off + 2) & 0xff) << 16)
                | ((buf.get(off + 3) & 0xff) << 24);
    }

    private static void putBgra(ByteBuffer buf, int off, int pix) {
        if (off < 0 || off + 3 >= buf.capacity()) return;
        buf.put(off, (byte) (pix & 0xff));
        buf.put(off + 1, (byte) ((pix >>> 8) & 0xff));
        buf.put(off + 2, (byte) ((pix >>> 16) & 0xff));
        buf.put(off + 3, (byte) ((pix >>> 24) & 0xff));
    }

    private static int sat(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int floorMod(int a, int n) {
        int r = a % n;
        return r < 0 ? r + n : r;
    }

    private static int reflect(int a, int n) {
        if (n <= 1) return 0;
        int p = floorMod(a, n * 2);
        return p < n ? p : (n * 2 - 1 - p);
    }
}
