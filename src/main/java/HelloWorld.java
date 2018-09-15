import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.nuklear.*;
import org.lwjgl.opengl.*;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.system.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.*;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.BufferUtils.createByteBuffer;
import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.nuklear.Nuklear.*;
import static org.lwjgl.stb.STBTruetype.*;
import static org.lwjgl.stb.STBTruetype.stbtt_ScaleForPixelHeight;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.ARBDebugOutput.*;

public class HelloWorld {

    private static final NkAllocator ALLOCATOR;

    private static final NkDrawVertexLayoutElement.Buffer VERTEX_LAYOUT;
    private static final int BUFFER_INITIAL_SIZE = 4 * 1024;

    private static final int MAX_VERTEX_BUFFER  = 512 * 1024;
    private static final int MAX_ELEMENT_BUFFER = 128 * 1024;
    // The window handle
    private long window;


    private NkBuffer          cmds         = NkBuffer.create();
    private NkDrawNullTexture null_texture = NkDrawNullTexture.create();
    private final ByteBuffer ttf;
    private List<Integer> joystickIds = new ArrayList<>();

    private NkContext nk = NkContext.create();
    private NkUserFont default_font = NkUserFont.create();
    private NkRect rect = NkRect.create();

    private byte[] movement = new byte[9];

    private int[] hat_buttons = {0};
    private int
            width,
            height;

    private int
            display_width,
            display_height;
    static {
        ALLOCATOR = NkAllocator.create()
                .alloc((handle, old, size) -> nmemAllocChecked(size))
                .mfree((handle, ptr) -> nmemFree(ptr));
        VERTEX_LAYOUT = NkDrawVertexLayoutElement.create(4)
                .position(0).attribute(NK_VERTEX_POSITION).format(NK_FORMAT_FLOAT).offset(0)
                .position(1).attribute(NK_VERTEX_TEXCOORD).format(NK_FORMAT_FLOAT).offset(8)
                .position(2).attribute(NK_VERTEX_COLOR).format(NK_FORMAT_R8G8B8A8).offset(16)
                .position(3).attribute(NK_VERTEX_ATTRIBUTE_COUNT).format(NK_FORMAT_COUNT).offset(0)
                .flip();
    }
    private int vbo, vao, ebo;
    private int prog;
    private int vert_shdr;
    private int frag_shdr;
    private int uniform_tex;
    private int uniform_proj;
    private InetAddress address;
    private DatagramPacket packet;
    private DatagramSocket datagramSocket;
    private int gi = 0;

    public HelloWorld() {
        try {
            this.ttf = ioResourceToByteBuffer("FiraSans.ttf", 512 * 1024);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void run() {
        System.out.println("Hello LWJGL " + Version.getVersion() + "!");

        init();
        loop();

        // Free the window callbacks and destroy the window
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);

        // Terminate GLFW and free the error callback
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    public static final void intToByteArray(byte[] array, int value, int offset) {
        array[offset] = (byte)value;
        array[offset + 1] = (byte)(value >>> 8);
        array[offset + 2] = (byte)(value >>> 16);
        array[offset + 3] = (byte)(value >>> 24);
    }
    private void init() {
        try {
            address = InetAddress.getByName("192.168.1.25");
            packet = new DatagramPacket(
                    movement, movement.length, address, 4100
            );
            movement[8] = 39;
            datagramSocket = new DatagramSocket();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


        // Setup an error callback. The default implementation
        // will print the error message in System.err.
        GLFWErrorCallback.createPrint(System.err).set();

        // Initialize GLFW. Most GLFW functions will not work before doing this.
        if ( !glfwInit() )
            throw new IllegalStateException("Unable to initialize GLFW");

        // Configure GLFW
        glfwDefaultWindowHints(); // optional, the current window hints are already the default
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // the window will stay hidden after creation
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // the window will be resizable
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);

        // Create the window
        window = glfwCreateWindow(300, 300, "Hello World!", NULL, NULL);
        if ( window == NULL )
            throw new RuntimeException("Failed to create the GLFW window");

        for (int jid = GLFW_JOYSTICK_1;  jid <= GLFW_JOYSTICK_LAST;  jid++)
        {
            if (glfwJoystickPresent(jid))
                joystickIds.add(jid);
        }

        // Setup a key callback. It will be called every time a key is pressed, repeated or released.
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if ( key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE )
                glfwSetWindowShouldClose(window, true); // We will detect this in the rendering loop
        });
        glfwSetJoystickCallback(((jid, event) -> {
            System.out.println(jid);
            if (event == GLFW_CONNECTED)
                joystickIds.add(jid);
            else if (event == GLFW_DISCONNECTED)
                joystickIds.remove((Integer)jid);
        }));
        glfwSetCursorPosCallback(window, (window, xpos, ypos) -> nk_input_motion(nk, (int)xpos, (int)ypos));
        glfwSetMouseButtonCallback(window, (window, button, action, mods) -> {
            try (MemoryStack stack = stackPush()) {
                DoubleBuffer cx = stack.mallocDouble(1);
                DoubleBuffer cy = stack.mallocDouble(1);

                glfwGetCursorPos(window, cx, cy);

                int x = (int)cx.get(0);
                int y = (int)cy.get(0);

                int nkButton;
                switch (button) {
                    case GLFW_MOUSE_BUTTON_RIGHT:
                        nkButton = NK_BUTTON_RIGHT;
                        break;
                    case GLFW_MOUSE_BUTTON_MIDDLE:
                        nkButton = NK_BUTTON_MIDDLE;
                        break;
                    default:
                        nkButton = NK_BUTTON_LEFT;
                }
                nk_input_button(nk, nkButton, x, y, action == GLFW_PRESS);
            }
        });

        // Get the thread stack and push a new frame
        try ( MemoryStack stack = stackPush() ) {
            IntBuffer pWidth = stack.mallocInt(1); // int*
            IntBuffer pHeight = stack.mallocInt(1); // int*

            // Get the window size passed to glfwCreateWindow
            glfwGetWindowSize(window, pWidth, pHeight);

            // Get the resolution of the primary monitor
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

            // Center the window
            glfwSetWindowPos(
                    window,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2
            );
        } // the stack frame is popped automatically

        // Make the OpenGL context current
        glfwMakeContextCurrent(window);

        GLCapabilities caps      = GL.createCapabilities();
        Callback       debugProc = GLUtil.setupDebugMessageCallback();

        if (caps.OpenGL43) {
            GL43.glDebugMessageControl(GL43.GL_DEBUG_SOURCE_API, GL43.GL_DEBUG_TYPE_OTHER, GL43.GL_DEBUG_SEVERITY_NOTIFICATION, (IntBuffer)null, false);
        } else if (caps.GL_KHR_debug) {
            KHRDebug.glDebugMessageControl(
                    KHRDebug.GL_DEBUG_SOURCE_API,
                    KHRDebug.GL_DEBUG_TYPE_OTHER,
                    KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION,
                    (IntBuffer)null,
                    false
            );
        } else if (caps.GL_ARB_debug_output) {
            glDebugMessageControlARB(GL_DEBUG_SOURCE_API_ARB, GL_DEBUG_TYPE_OTHER_ARB, GL_DEBUG_SEVERITY_LOW_ARB, (IntBuffer)null, false);
        }

        // Enable v-sync
        glfwSwapInterval(1);

        // Make the window visible
        glfwShowWindow(window);


        nk_init(nk, ALLOCATOR, null);setupContext();
        int BITMAP_W = 1024;
        int BITMAP_H = 1024;

        int FONT_HEIGHT = 18;
        int fontTexID   = glGenTextures();

        STBTTFontinfo fontInfo = STBTTFontinfo.create();
        STBTTPackedchar.Buffer cdata    = STBTTPackedchar.create(95);

        float scale;
        float descent;

        try (MemoryStack stack = stackPush()) {
            stbtt_InitFont(fontInfo, ttf);
            scale = stbtt_ScaleForPixelHeight(fontInfo, FONT_HEIGHT);

            IntBuffer d = stack.mallocInt(1);
            stbtt_GetFontVMetrics(fontInfo, null, d, null);
            descent = d.get(0) * scale;

            ByteBuffer bitmap = memAlloc(BITMAP_W * BITMAP_H);

            STBTTPackContext pc = STBTTPackContext.mallocStack(stack);
            stbtt_PackBegin(pc, bitmap, BITMAP_W, BITMAP_H, 0, 1, NULL);
            stbtt_PackSetOversampling(pc, 4, 4);
            stbtt_PackFontRange(pc, ttf, 0, FONT_HEIGHT, 32, cdata);
            stbtt_PackEnd(pc);

            // Convert R8 to RGBA8
            ByteBuffer texture = memAlloc(BITMAP_W * BITMAP_H * 4);
            for (int i = 0; i < bitmap.capacity(); i++) {
                texture.putInt((bitmap.get(i) << 24) | 0x00FFFFFF);
            }
            texture.flip();

            glBindTexture(GL_TEXTURE_2D, fontTexID);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, BITMAP_W, BITMAP_H, 0, GL_RGBA, GL_UNSIGNED_INT_8_8_8_8_REV, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

            memFree(texture);
            memFree(bitmap);
        }

        default_font
                .width((handle, h, text, len) -> {
                    float text_width = 0;
                    try (MemoryStack stack = stackPush()) {
                        IntBuffer unicode = stack.mallocInt(1);

                        int glyph_len = nnk_utf_decode(text, memAddress(unicode), len);
                        int text_len  = glyph_len;

                        if (glyph_len == 0) {
                            return 0;
                        }

                        IntBuffer advance = stack.mallocInt(1);
                        while (text_len <= len && glyph_len != 0) {
                            if (unicode.get(0) == NK_UTF_INVALID) {
                                break;
                            }

                            /* query currently drawn glyph information */
                            stbtt_GetCodepointHMetrics(fontInfo, unicode.get(0), advance, null);
                            text_width += advance.get(0) * scale;

                            /* offset next glyph */
                            glyph_len = nnk_utf_decode(text + text_len, memAddress(unicode), len - text_len);
                            text_len += glyph_len;
                        }
                    }
                    return text_width;
                })
                .height(FONT_HEIGHT)
                .query((handle, font_height, glyph, codepoint, next_codepoint) -> {
                    try (MemoryStack stack = stackPush()) {
                        FloatBuffer x = stack.floats(0.0f);
                        FloatBuffer y = stack.floats(0.0f);

                        STBTTAlignedQuad q       = STBTTAlignedQuad.mallocStack(stack);
                        IntBuffer        advance = stack.mallocInt(1);

                        stbtt_GetPackedQuad(cdata, BITMAP_W, BITMAP_H, codepoint - 32, x, y, q, false);
                        stbtt_GetCodepointHMetrics(fontInfo, codepoint, advance, null);

                        NkUserFontGlyph ufg = NkUserFontGlyph.create(glyph);

                        ufg.width(q.x1() - q.x0());
                        ufg.height(q.y1() - q.y0());
                        ufg.offset().set(q.x0(), q.y0() + (FONT_HEIGHT + descent));
                        ufg.xadvance(advance.get(0) * scale);
                        ufg.uv(0).set(q.s0(), q.t0());
                        ufg.uv(1).set(q.s1(), q.t1());
                    }
                })
                .texture(it -> it
                        .id(fontTexID));

        nk_style_set_font(nk, default_font);
    }

    private void setupContext() {
        String NK_SHADER_VERSION = Platform.get() == Platform.MACOSX ? "#version 150\n" : "#version 300 es\n";
        String vertex_shader =
                NK_SHADER_VERSION +
                        "uniform mat4 ProjMtx;\n" +
                        "in vec2 Position;\n" +
                        "in vec2 TexCoord;\n" +
                        "in vec4 Color;\n" +
                        "out vec2 Frag_UV;\n" +
                        "out vec4 Frag_Color;\n" +
                        "void main() {\n" +
                        "   Frag_UV = TexCoord;\n" +
                        "   Frag_Color = Color;\n" +
                        "   gl_Position = ProjMtx * vec4(Position.xy, 0, 1);\n" +
                        "}\n";
        String fragment_shader =
                NK_SHADER_VERSION +
                        "precision mediump float;\n" +
                        "uniform sampler2D Texture;\n" +
                        "in vec2 Frag_UV;\n" +
                        "in vec4 Frag_Color;\n" +
                        "out vec4 Out_Color;\n" +
                        "void main(){\n" +
                        "   Out_Color = Frag_Color * texture(Texture, Frag_UV.st);\n" +
                        "}\n";

        nk_buffer_init(cmds, ALLOCATOR, BUFFER_INITIAL_SIZE);
        prog = glCreateProgram();
        vert_shdr = glCreateShader(GL_VERTEX_SHADER);
        frag_shdr = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(vert_shdr, vertex_shader);
        glShaderSource(frag_shdr, fragment_shader);
        glCompileShader(vert_shdr);
        glCompileShader(frag_shdr);
        if (glGetShaderi(vert_shdr, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException();
        }
        if (glGetShaderi(frag_shdr, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException();
        }
        glAttachShader(prog, vert_shdr);
        glAttachShader(prog, frag_shdr);
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException();
        }

        uniform_tex = glGetUniformLocation(prog, "Texture");
        uniform_proj = glGetUniformLocation(prog, "ProjMtx");
        int attrib_pos = glGetAttribLocation(prog, "Position");
        int attrib_uv  = glGetAttribLocation(prog, "TexCoord");
        int attrib_col = glGetAttribLocation(prog, "Color");

        {
            // buffer setup
            vbo = glGenBuffers();
            ebo = glGenBuffers();
            vao = glGenVertexArrays();

            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);

            glEnableVertexAttribArray(attrib_pos);
            glEnableVertexAttribArray(attrib_uv);
            glEnableVertexAttribArray(attrib_col);

            glVertexAttribPointer(attrib_pos, 2, GL_FLOAT, false, 20, 0);
            glVertexAttribPointer(attrib_uv, 2, GL_FLOAT, false, 20, 8);
            glVertexAttribPointer(attrib_col, 4, GL_UNSIGNED_BYTE, true, 20, 16);
        }

        {
            // null texture setup
            int nullTexID = glGenTextures();

            null_texture.texture().id(nullTexID);
            null_texture.uv().set(0.5f, 0.5f);

            glBindTexture(GL_TEXTURE_2D, nullTexID);
            try (MemoryStack stack = stackPush()) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_INT_8_8_8_8_REV, stack.ints(0xFFFFFFFF));
            }
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        }

        glBindTexture(GL_TEXTURE_2D, 0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }
    private void newFrame() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);

            glfwGetWindowSize(window, w, h);
            width = w.get(0);
            height = h.get(0);

            glfwGetFramebufferSize(window, w, h);
            display_width = w.get(0);
            display_height = h.get(0);
        }

        nk_input_begin(nk);
            glfwPollEvents();

        NkMouse mouse = nk.input().mouse();
        if (mouse.grab()) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN);
        } else if (mouse.grabbed()) {
            float prevX = mouse.prev().x();
            float prevY = mouse.prev().y();
            glfwSetCursorPos(window, prevX, prevY);
            mouse.pos().x(prevX);
            mouse.pos().y(prevY);
        } else if (mouse.ungrab()) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }

        nk_input_end(nk);
    }

    private void loop() {
        // This line is critical for LWJGL's interoperation with GLFW's
        // OpenGL context, or any context that is managed externally.
        // LWJGL detects the context that is current in the current thread,
        // creates the GLCapabilities instance and makes the OpenGL
        // bindings available for use.
        GL.createCapabilities();

        // Set the clear color
        glClearColor(1.0f, 0.0f, 0.0f, 0.0f);

        // Run the rendering loop until the user has attempted to close
        // the window or has pressed the ESCAPE key.
        while ( !glfwWindowShouldClose(window) ) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the framebuffer
            newFrame();

            if (nk_begin(nk,
                    "Joysticks",
                    nk_rect(width - 200.f, 0.f, 200.f, (float) height, rect),
                    NK_WINDOW_MINIMIZABLE |
                            NK_WINDOW_TITLE))
            {
                nk_layout_row_dynamic(nk, 30, 1);

                nk_checkbox_label(nk, "Hat buttons", hat_buttons);

                if (!joystickIds.isEmpty())
                {
                    for (int i = 0;  i < joystickIds.size();  i++)
                    {
                        if (nk_button_label(nk,joystickIds.get(i) + ": " + glfwGetJoystickName(joystickIds.get(i))))
                            nk_window_set_focus(nk, joystickIds.get(i) + ": " + glfwGetJoystickName(joystickIds.get(i)));
                    }
                }
                else
                    nk_label(nk, "No joysticks connected", NK_TEXT_LEFT);
            }

            nk_end(nk);

            for (int i = 0;  i < joystickIds.size();  i++)
            {
                if (nk_begin(nk,
                        joystickIds.get(i) + ": " + glfwGetJoystickName(joystickIds.get(i)),
                        nk_rect(i * 20.f, i * 20.f, 550.f, 570.f, rect),
                        NK_WINDOW_BORDER |
                                NK_WINDOW_MOVABLE |
                                NK_WINDOW_SCALABLE |
                                NK_WINDOW_MINIMIZABLE |
                                NK_WINDOW_TITLE))
                {
                    int j, axis_count, button_count, hat_count;
                    FloatBuffer axes;
                    ByteBuffer buttons;
                    ByteBuffer hats;
                    GLFWGamepadState state = GLFWGamepadState.create();

                    nk_layout_row_dynamic(nk, 30, 1);
                    nk_label(nk, "Hardware GUID " + glfwGetJoystickGUID(joystickIds.get(i)), NK_TEXT_LEFT);
                    nk_label(nk, "Joystick state", NK_TEXT_LEFT);

                    axes = glfwGetJoystickAxes(joystickIds.get(i));
                    axis_count = axes.remaining();
                    buttons = glfwGetJoystickButtons(joystickIds.get(i));
                    button_count = buttons.remaining();
                    hats = glfwGetJoystickHats(joystickIds.get(i));
                    hat_count = hats.remaining();

                    if (hat_buttons[0] == 0)
                        button_count -= hat_count * 4;

                    for (j = 0;  j < axis_count;  j++)
                        nk_slide_float(nk, -1.f, axes.get(j), 1.f, 0.1f);
                    if (glfwGetJoystickName(joystickIds.get(i)).contains("Xbox") && gi++ > 1) {
                        gi = 0;
                        try {
                            int l,r;

                            l = -(int)(1023 * axes.get(1));
                            if (Math.abs(l) < 80) {
                                l = 0;
                            }

                            r = -(int)(1023 * axes.get(3));
                            if (Math.abs(r) < 80) {
                                r = 0;
                            }
                            intToByteArray(movement, l, 0);
                            intToByteArray(movement, r, 4);
                            datagramSocket.send(packet);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    nk_layout_row_dynamic(nk, 30, 12);

                    for (j = 0;  j < button_count;  j++)
                    {
                        nk_select_label(nk, "" + (j + 1), NK_TEXT_CENTERED, buttons.get(j) != 0);
                    }

                    nk_layout_row_dynamic(nk, 30, 8);

                    for (j = 0;  j < hat_count;  j++)
                        hat_widget(nk, hats.get(j));

                    nk_layout_row_dynamic(nk, 30, 1);

                    if (glfwGetGamepadState(joystickIds.get(i), state))
                    {
                        int hat = 0;
                    String names[] =
                            {
                                    "A", "B", "X", "Y",
                                    "LB", "RB",
                                    "Back", "Start", "Guide",
                                    "LT", "RT",
                            };

                        nk_label(nk, "Gamepad state: " + glfwGetGamepadName(joystickIds.get(i)), NK_TEXT_LEFT);

                        nk_layout_row_dynamic(nk, 30, 2);

                        for (j = 0;  j <= GLFW_GAMEPAD_AXIS_LAST;  j++)
                            nk_slide_float(nk, -1.f, state.axes(j), 1.f, 0.1f);

                        nk_layout_row_dynamic(nk, 30, GLFW_GAMEPAD_BUTTON_LAST + 1 - 4);

                        for (j = 0;  j <= GLFW_GAMEPAD_BUTTON_LAST - 4;  j++)
                            nk_select_label(nk, names[j], NK_TEXT_CENTERED, state.buttons(j) == 1);

                        if (state.buttons(GLFW_GAMEPAD_BUTTON_DPAD_UP) == 1)
                            hat |= GLFW_HAT_UP;
                        if (state.buttons(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT) == 1)
                            hat |= GLFW_HAT_RIGHT;
                        if (state.buttons(GLFW_GAMEPAD_BUTTON_DPAD_DOWN) == 1)
                            hat |= GLFW_HAT_DOWN;
                        if (state.buttons(GLFW_GAMEPAD_BUTTON_DPAD_LEFT) == 1)
                            hat |= GLFW_HAT_LEFT;

                        nk_layout_row_dynamic(nk, 30, 8);
                        hat_widget(nk, hat);
                    }
                else
                    nk_label(nk, "Joystick has no gamepad mapping", NK_TEXT_LEFT);
                }

                nk_end(nk);
            }

            render(NK_ANTI_ALIASING_ON, MAX_VERTEX_BUFFER, MAX_ELEMENT_BUFFER);

            glfwSwapBuffers(window); // swap the color buffers
        }
    }

    static void hat_widget(NkContext nk, int state)
    {
        float radius;
        NkRect area = NkRect.create();
        NkVec2 center = NkVec2.create();
        NkColor color = NkColor.create();

        if (nk_widget(area, nk) == NK_WIDGET_INVALID)
        return;

        center = nk_vec2(area.x() + area.w() / 2.f, area.y() + area.h() / 2.f, center);
        radius = Math.min(area.w(), area.h()) / 2.f;

        nk_stroke_circle(nk_window_get_canvas(nk),
                nk_rect(center.x() - radius,
                        center.y() - radius,
                        radius * 2.f,
                        radius * 2.f, area),
                1.f,
                nk_rgb(175, 175, 175, color));

        if (state == 1)
        {
            float angles[] =
                {
                        0.f,           0.f,
                        (float) (Math.PI * 1.5f), (float) (Math.PI * 1.75f),
                        (float) Math.PI,         0.f,
                        (float) (Math.PI * 1.25f), 0.f,
                        (float) (Math.PI * 0.5f), (float) (Math.PI * 0.25f),
                        0.f,           0.f,
                        (float) (Math.PI * 0.75f), 0.f,
                };
        float cosa = (float) Math.cos(angles[state]);
        float sina = (float) Math.sin(angles[state]);
        NkVec2 p0 = nk_vec2(0.f, -radius, NkVec2.create());
        NkVec2 p1 = nk_vec2( radius / 2.f, -radius / 3.f, NkVec2.create());
        NkVec2 p2 = nk_vec2(-radius / 2.f, -radius / 3.f, NkVec2.create());

            nk_fill_triangle(nk_window_get_canvas(nk),
                    center.x() + cosa * p0.x() + sina * p0.y(),
                    center.y() + cosa * p0.y() - sina * p0.x(),
                    center.x() + cosa * p1.x() + sina * p1.y(),
                    center.y() + cosa * p1.y() - sina * p1.x(),
                    center.x() + cosa * p2.x() + sina * p2.y(),
                    center.y() + cosa * p2.y() - sina * p2.x(),
                    nk_rgb(175, 175, 175, color));
        }
    }

    private void render(int AA, int max_vertex_buffer, int max_element_buffer) {
        try (MemoryStack stack = stackPush()) {
            // setup global state
            glEnable(GL_BLEND);
            glBlendEquation(GL_FUNC_ADD);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDisable(GL_CULL_FACE);
            glDisable(GL_DEPTH_TEST);
            glEnable(GL_SCISSOR_TEST);
            glActiveTexture(GL_TEXTURE0);

            // setup program
            glUseProgram(prog);
            glUniform1i(uniform_tex, 0);
            glUniformMatrix4fv(uniform_proj, false, stack.floats(
                    2.0f / width, 0.0f, 0.0f, 0.0f,
                    0.0f, -2.0f / height, 0.0f, 0.0f,
                    0.0f, 0.0f, -1.0f, 0.0f,
                    -1.0f, 1.0f, 0.0f, 1.0f
            ));
            glViewport(0, 0, display_width, display_height);
        }

        {
            // convert from command queue into draw list and draw to screen

            // allocate vertex and element buffer
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);

            glBufferData(GL_ARRAY_BUFFER, max_vertex_buffer, GL_STREAM_DRAW);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, max_element_buffer, GL_STREAM_DRAW);

            // load draw vertices & elements directly into vertex + element buffer
            ByteBuffer vertices = Objects.requireNonNull(glMapBuffer(GL_ARRAY_BUFFER, GL_WRITE_ONLY, max_vertex_buffer, null));
            ByteBuffer elements = Objects.requireNonNull(glMapBuffer(GL_ELEMENT_ARRAY_BUFFER, GL_WRITE_ONLY, max_element_buffer, null));
            try (MemoryStack stack = stackPush()) {
                // fill convert configuration
                NkConvertConfig config = NkConvertConfig.callocStack(stack)
                        .vertex_layout(VERTEX_LAYOUT)
                        .vertex_size(20)
                        .vertex_alignment(4)
                        .null_texture(null_texture)
                        .circle_segment_count(22)
                        .curve_segment_count(22)
                        .arc_segment_count(22)
                        .global_alpha(1.0f)
                        .shape_AA(AA)
                        .line_AA(AA);

                // setup buffers to load vertices and elements
                NkBuffer vbuf = NkBuffer.mallocStack(stack);
                NkBuffer ebuf = NkBuffer.mallocStack(stack);

                nk_buffer_init_fixed(vbuf, vertices/*, max_vertex_buffer*/);
                nk_buffer_init_fixed(ebuf, elements/*, max_element_buffer*/);
                nk_convert(nk, cmds, vbuf, ebuf, config);
            }
            glUnmapBuffer(GL_ELEMENT_ARRAY_BUFFER);
            glUnmapBuffer(GL_ARRAY_BUFFER);

            // iterate over and execute each draw command
            float fb_scale_x = (float)display_width / (float)width;
            float fb_scale_y = (float)display_height / (float)height;

            long offset = NULL;
            for (NkDrawCommand cmd = nk__draw_begin(nk, cmds); cmd != null; cmd = nk__draw_next(cmd, cmds, nk)) {
                if (cmd.elem_count() == 0) {
                    continue;
                }
                glBindTexture(GL_TEXTURE_2D, cmd.texture().id());
                glScissor(
                        (int)(cmd.clip_rect().x() * fb_scale_x),
                        (int)((height - (int)(cmd.clip_rect().y() + cmd.clip_rect().h())) * fb_scale_y),
                        (int)(cmd.clip_rect().w() * fb_scale_x),
                        (int)(cmd.clip_rect().h() * fb_scale_y)
                );
                glDrawElements(GL_TRIANGLES, cmd.elem_count(), GL_UNSIGNED_SHORT, offset);
                offset += cmd.elem_count() * 2;
            }
            nk_clear(nk);
        }

        // default OpenGL state
        glUseProgram(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
    }


    public static ByteBuffer ioResourceToByteBuffer(String resource, int bufferSize) throws IOException {
        ByteBuffer buffer;

        Path path = Paths.get(resource);
        if (Files.isReadable(path)) {
            try (SeekableByteChannel fc = Files.newByteChannel(path)) {
                buffer = createByteBuffer((int)fc.size() + 1);
                while (fc.read(buffer) != -1) {
                    ;
                }
            }
        } else {
            try (
                    InputStream source = HelloWorld.class.getClassLoader().getResourceAsStream(resource);
                    ReadableByteChannel rbc = Channels.newChannel(source)
            ) {
                buffer = createByteBuffer(bufferSize);

                while (true) {
                    int bytes = rbc.read(buffer);
                    if (bytes == -1) {
                        break;
                    }
                    if (buffer.remaining() == 0) {
                        buffer = resizeBuffer(buffer, buffer.capacity() * 3 / 2); // 50%
                    }
                }
            }
        }

        buffer.flip();
        return buffer.slice();
    }

    private static ByteBuffer resizeBuffer(ByteBuffer buffer, int newCapacity) {
        ByteBuffer newBuffer = BufferUtils.createByteBuffer(newCapacity);
        buffer.flip();
        newBuffer.put(buffer);
        return newBuffer;
    }
    public static void main(String[] args) {
        new HelloWorld().run();
    }

}