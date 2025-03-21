package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizon.gtnhlib.client.renderer.stacks.IStateStack;
import com.gtnewhorizon.gtnhlib.client.renderer.vbo.VBOManager;
import com.gtnewhorizons.angelica.AngelicaMod;
import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.glsm.stacks.AlphaStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.BlendStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.BooleanStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.Color4Stack;
import com.gtnewhorizons.angelica.glsm.stacks.ColorMaskStack;
import com.gtnewhorizons.angelica.glsm.stacks.DepthStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.FogStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.IntegerStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.LightModelStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.LightStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.MaterialStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.MatrixModeStack;
import com.gtnewhorizons.angelica.glsm.stacks.ViewPortStateStack;
import com.gtnewhorizons.angelica.glsm.states.Color4;
import com.gtnewhorizons.angelica.glsm.states.ISettableState;
import com.gtnewhorizons.angelica.glsm.states.TextureBinding;
import com.gtnewhorizons.angelica.glsm.states.TextureUnitArray;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfo;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfoCache;
import com.gtnewhorizons.angelica.glsm.texture.TextureTracker;
import com.gtnewhorizons.angelica.hudcaching.HUDCaching;
import com.gtnewhorizons.angelica.loading.AngelicaTweaker;
import cpw.mods.fml.relauncher.ReflectionHelper;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntStack;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gbuffer_overrides.state.StateTracker;
import net.coderbot.iris.gl.blending.AlphaTestStorage;
import net.coderbot.iris.gl.blending.BlendModeStorage;
import net.coderbot.iris.gl.blending.DepthColorStorage;
import net.coderbot.iris.gl.state.StateUpdateNotifiers;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.samplers.IrisSamplers;
import net.coderbot.iris.texture.pbr.PBRTextureManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.ARBMultitexture;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Drawable;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.KHRDebug;

import java.lang.Math;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

import static com.gtnewhorizons.angelica.loading.AngelicaTweaker.LOGGER;

@SuppressWarnings("unused") // Used in ASM
public class GLStateManager {
    public static ContextCapabilities capabilities;

    @Getter protected static boolean poppingAttributes;
    public static boolean BYPASS_CACHE = Boolean.parseBoolean(System.getProperty("angelica.disableGlCache", "false"));
    public static final int MAX_ATTRIB_STACK_DEPTH = GL11.glGetInteger(GL11.GL_MAX_ATTRIB_STACK_DEPTH);
    public static final int MAX_MODELVIEW_STACK_DEPTH = GL11.glGetInteger(GL11.GL_MAX_MODELVIEW_STACK_DEPTH);
    public static final int MAX_PROJECTION_STACK_DEPTH = GL11.glGetInteger(GL11.GL_MAX_PROJECTION_STACK_DEPTH);
    public static final int MAX_TEXTURE_STACK_DEPTH = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_STACK_DEPTH);
    public static final int MAX_TEXTURE_UNITS = GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS);

    public static final GLFeatureSet HAS_MULTIPLE_SET = new GLFeatureSet();

    @Getter protected static boolean NVIDIA;
    @Getter protected static boolean AMD;
    @Getter protected static boolean INTEL;
    @Getter protected static boolean MESA;

    // GLStateManager State Trackers
    private static final IntStack attribs = new IntArrayList(MAX_ATTRIB_STACK_DEPTH);
    protected static final IntegerStateStack activeTextureUnit = new IntegerStateStack(0);
    protected static final IntegerStateStack shadeModelState = new IntegerStateStack(GL11.GL_SMOOTH);

    @Getter protected static final TextureUnitArray textures = new TextureUnitArray();
    @Getter protected static final BlendStateStack blendState = new BlendStateStack();
    @Getter protected static final BooleanStateStack blendMode = new BooleanStateStack(GL11.GL_BLEND);
    @Getter protected static final BooleanStateStack scissorTest = new BooleanStateStack(GL11.GL_SCISSOR_TEST);
    @Getter protected static final DepthStateStack depthState = new DepthStateStack();
    @Getter protected static final BooleanStateStack depthTest = new BooleanStateStack(GL11.GL_DEPTH_TEST);

    @Getter protected static final FogStateStack fogState = new FogStateStack();
    @Getter protected static final BooleanStateStack fogMode = new BooleanStateStack(GL11.GL_FOG);
    @Getter protected static final Color4Stack color = new Color4Stack();
    @Getter protected static final Color4Stack clearColor = new Color4Stack(new Color4(0.0F, 0.0F, 0.0F, 0.0F));
    @Getter protected static final ColorMaskStack colorMask = new ColorMaskStack();
    @Getter protected static final BooleanStateStack cullState = new BooleanStateStack(GL11.GL_CULL_FACE);
    @Getter protected static final AlphaStateStack alphaState = new AlphaStateStack();
    @Getter protected static final BooleanStateStack alphaTest = new BooleanStateStack(GL11.GL_ALPHA_TEST);

    @Getter protected static final BooleanStateStack lightingState = new BooleanStateStack(GL11.GL_LIGHTING);
    @Getter protected static final BooleanStateStack rescaleNormalState = new BooleanStateStack(GL12.GL_RESCALE_NORMAL);

    @Getter protected static final MatrixModeStack matrixMode = new MatrixModeStack();
    @Getter protected static final Matrix4fStack modelViewMatrix = new Matrix4fStack(MAX_MODELVIEW_STACK_DEPTH);
    @Getter protected static final Matrix4fStack projectionMatrix = new Matrix4fStack(MAX_PROJECTION_STACK_DEPTH);

    @Getter protected static final BooleanStateStack[] lightStates = new BooleanStateStack[8];
    @Getter protected static final LightStateStack[] lightDataStates = new LightStateStack[8];
    @Getter protected static final BooleanStateStack colorMaterial = new BooleanStateStack(GL11.GL_COLOR_MATERIAL);
    @Getter protected static final IntegerStateStack colorMaterialFace = new IntegerStateStack(GL11.GL_FRONT_AND_BACK);
    @Getter protected static final IntegerStateStack colorMaterialParameter = new IntegerStateStack(GL11.GL_AMBIENT_AND_DIFFUSE);
    @Getter protected static final LightModelStateStack lightModel = new LightModelStateStack();

    @Getter protected static final MaterialStateStack frontMaterial = new MaterialStateStack(GL11.GL_FRONT);
    @Getter protected static final MaterialStateStack backMaterial = new MaterialStateStack(GL11.GL_BACK);

    private static final MethodHandle MAT4_STACK_CURR_DEPTH;

    static {
        for (int i = 0; i < lightStates.length; i ++) {
            lightStates[i] = new BooleanStateStack(GL11.GL_LIGHT0 + i);
            lightDataStates[i] = new LightStateStack(GL11.GL_LIGHT0 + i);
        }

        try {
            Field curr = ReflectionHelper.findField(Matrix4fStack.class, "curr");
            curr.setAccessible(true);
            MAT4_STACK_CURR_DEPTH = MethodHandles.lookup().unreflectGetter(curr);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Getter protected static final ViewPortStateStack viewportState = new ViewPortStateStack();

    @Getter protected static int activeProgram = 0;

    public static void reset() {
        runningSplash = true;
        while(!attribs.isEmpty()) {
            attribs.popInt();
        }

        List<IStateStack<?>> stacks = Feature.maskToFeatures(GL11.GL_ALL_ATTRIB_BITS);
        int size = stacks.size();

        for(int i = 0; i < size; i++) {
            IStateStack<?> stack = stacks.get(i);

            while(!stack.isEmpty()) {
                stack.pop();
            }
        }

        modelViewMatrix.clear();
        projectionMatrix.clear();
    }

    // Iris Listeners
    private static Runnable blendFuncListener = null;
    private static Runnable fogToggleListener = null;
    private static Runnable fogModeListener = null;
    private static Runnable fogStartListener = null;
    private static Runnable fogEndListener = null;
    private static Runnable fogDensityListener = null;

    // Thread Checking
    @Getter private static final Thread MainThread = Thread.currentThread();
    private static Thread CurrentThread = MainThread;
    @Setter @Getter private static boolean runningSplash = true;

    private static int glListMode = 0;
    private static int glListNesting = 0;
    private static int glListId = -1;
    private static final Map<IStateStack<?>, ISettableState<?>> glListStates = new Object2ObjectArrayMap<>();
    private static final Int2ObjectMap<Set<Map.Entry<IStateStack<?>, ISettableState<?>>>> glListChanges = new Int2ObjectOpenHashMap<>();



    public static class GLFeatureSet extends IntOpenHashSet {

        private static final long serialVersionUID = 8558779940775721010L;

        public GLFeatureSet addFeature(int feature) {
            super.add(feature);
            return this;
        }

    }

    public static void preInit() {
        capabilities = GLContext.getCapabilities();
        HAS_MULTIPLE_SET
            .addFeature(GL11.GL_ACCUM_CLEAR_VALUE)
            .addFeature(GL14.GL_BLEND_COLOR)
            .addFeature(GL11.GL_COLOR_CLEAR_VALUE)
            .addFeature(GL11.GL_COLOR_WRITEMASK)
            .addFeature(GL11.GL_CURRENT_COLOR)
            .addFeature(GL11.GL_CURRENT_NORMAL)
            .addFeature(GL11.GL_CURRENT_RASTER_COLOR)
            .addFeature(GL11.GL_CURRENT_RASTER_POSITION)
            .addFeature(GL11.GL_CURRENT_RASTER_TEXTURE_COORDS)
            .addFeature(GL11.GL_CURRENT_TEXTURE_COORDS)
            .addFeature(GL11.GL_DEPTH_RANGE)
            .addFeature(GL11.GL_FOG_COLOR)
            .addFeature(GL11.GL_LIGHT_MODEL_AMBIENT)
            .addFeature(GL11.GL_LINE_WIDTH_RANGE)
            .addFeature(GL11.GL_MAP1_GRID_DOMAIN)
            .addFeature(GL11.GL_MAP2_GRID_DOMAIN)
            .addFeature(GL11.GL_MAP2_GRID_SEGMENTS)
            .addFeature(GL11.GL_MAX_VIEWPORT_DIMS)
            .addFeature(GL11.GL_MODELVIEW_MATRIX)
            .addFeature(GL11.GL_POINT_SIZE_RANGE)
            .addFeature(GL11.GL_POLYGON_MODE)
            .addFeature(GL11.GL_PROJECTION_MATRIX)
            .addFeature(GL11.GL_SCISSOR_BOX)
            .addFeature(GL11.GL_TEXTURE_ENV_COLOR)
            .addFeature(GL11.GL_TEXTURE_MATRIX)
            .addFeature(GL11.GL_VIEWPORT);

        String glVendor = GL11.glGetString(GL11.GL_VENDOR);
        NVIDIA = glVendor.toLowerCase().contains("nvidia");
        AMD = glVendor.toLowerCase().contains("ati") || glVendor.toLowerCase().contains("amd");
        INTEL = glVendor.toLowerCase().contains("intel");
        MESA = glVendor.toLowerCase().contains("mesa");

        if(AMD) {
            // AMD Drivers seem to default to 0 for the matrix mode, so we need to set it to the default
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }

    }

    public static void init() {


        RenderSystem.initRenderer();

        if (AngelicaConfig.enableIris) {
            StateUpdateNotifiers.blendFuncNotifier = listener -> blendFuncListener = listener;
            StateUpdateNotifiers.fogToggleNotifier = listener -> fogToggleListener = listener;
            StateUpdateNotifiers.fogModeNotifier = listener -> fogModeListener = listener;
            StateUpdateNotifiers.fogStartNotifier = listener -> fogStartListener = listener;
            StateUpdateNotifiers.fogEndNotifier = listener -> fogEndListener = listener;
            StateUpdateNotifiers.fogDensityNotifier = listener -> fogDensityListener = listener;
        }
        if(BYPASS_CACHE) {
            LOGGER.info("GLStateManager cache bypassed");
        }
        if(AngelicaMod.lwjglDebug) {
            LOGGER.info("Enabling additional LWJGL debug output");

            GLDebug.setupDebugMessageCallback();
            GLDebug.initDebugState();

            GLDebug.debugMessage("Angelica Debug Annotator Initialized");
        }

    }

    public static boolean isMainThread() {
        return Thread.currentThread() == MainThread;
    }

    public static void assertMainThread() {
        if (Thread.currentThread() != CurrentThread && !runningSplash) {
            LOGGER.info("Call from not the Current Thread! - " + Thread.currentThread().getName() + " Current thread: " + CurrentThread.getName());
        }
    }

    public static boolean shouldBypassCache() {
        return BYPASS_CACHE || runningSplash;
    }

    // LWJGL Overrides
    public static void glEnable(int cap) {
        switch (cap) {
            case GL11.GL_ALPHA_TEST -> enableAlphaTest();
            case GL11.GL_BLEND -> enableBlend();
            case GL11.GL_CULL_FACE -> enableCull();
            case GL11.GL_DEPTH_TEST -> enableDepthTest();
            case GL11.GL_FOG -> enableFog();
            case GL11.GL_LIGHTING -> enableLighting();
            case GL11.GL_LIGHT0 -> enableLight(0);
            case GL11.GL_LIGHT1 -> enableLight(1);
            case GL11.GL_LIGHT2 -> enableLight(2);
            case GL11.GL_LIGHT3 -> enableLight(3);
            case GL11.GL_LIGHT4 -> enableLight(4);
            case GL11.GL_LIGHT5 -> enableLight(5);
            case GL11.GL_LIGHT6 -> enableLight(6);
            case GL11.GL_LIGHT7 -> enableLight(7);
            case GL11.GL_COLOR_MATERIAL -> enableColorMaterial();
            case GL11.GL_SCISSOR_TEST -> enableScissorTest();
            case GL11.GL_TEXTURE_2D -> enableTexture();
            case GL12.GL_RESCALE_NORMAL -> enableRescaleNormal();
            default -> GL11.glEnable(cap);
        }
    }

    public static void glDisable(int cap) {
        switch (cap) {
            case GL11.GL_ALPHA_TEST -> disableAlphaTest();
            case GL11.GL_BLEND -> disableBlend();
            case GL11.GL_CULL_FACE -> disableCull();
            case GL11.GL_DEPTH_TEST -> disableDepthTest();
            case GL11.GL_FOG -> disableFog();
            case GL11.GL_LIGHTING -> disableLighting();
            case GL11.GL_LIGHT0 -> disableLight(0);
            case GL11.GL_LIGHT1 -> disableLight(1);
            case GL11.GL_LIGHT2 -> disableLight(2);
            case GL11.GL_LIGHT3 -> disableLight(3);
            case GL11.GL_LIGHT4 -> disableLight(4);
            case GL11.GL_LIGHT5 -> disableLight(5);
            case GL11.GL_LIGHT6 -> disableLight(6);
            case GL11.GL_LIGHT7 -> disableLight(7);
            case GL11.GL_COLOR_MATERIAL -> disableColorMaterial();
            case GL11.GL_SCISSOR_TEST -> disableScissorTest();
            case GL11.GL_TEXTURE_2D -> disableTexture();
            case GL12.GL_RESCALE_NORMAL -> disableRescaleNormal();
            default -> GL11.glDisable(cap);
        }
    }

    public static boolean glIsEnabled(int cap) {
        if(shouldBypassCache()) {
            return GL11.glIsEnabled(cap);
        }
        return switch (cap) {
            case GL11.GL_ALPHA_TEST -> alphaTest.isEnabled();
            case GL11.GL_BLEND -> blendMode.isEnabled();
            case GL11.GL_CULL_FACE -> cullState.isEnabled();
            case GL11.GL_DEPTH_TEST -> depthTest.isEnabled();
            case GL11.GL_FOG -> fogMode.isEnabled();
            case GL11.GL_LIGHTING -> lightingState.isEnabled();
            case GL11.GL_LIGHT0 -> lightStates[0].isEnabled();
            case GL11.GL_LIGHT1 -> lightStates[1].isEnabled();
            case GL11.GL_LIGHT2 -> lightStates[2].isEnabled();
            case GL11.GL_LIGHT3 -> lightStates[3].isEnabled();
            case GL11.GL_LIGHT4 -> lightStates[4].isEnabled();
            case GL11.GL_LIGHT5 -> lightStates[5].isEnabled();
            case GL11.GL_LIGHT6 -> lightStates[6].isEnabled();
            case GL11.GL_LIGHT7 -> lightStates[7].isEnabled();
            case GL11.GL_COLOR_MATERIAL -> colorMaterial.isEnabled();
            case GL11.GL_SCISSOR_TEST -> scissorTest.isEnabled();
            case GL11.GL_TEXTURE_2D -> textures.getTextureUnitStates(activeTextureUnit.getValue()).isEnabled();
            case GL12.GL_RESCALE_NORMAL -> rescaleNormalState.isEnabled();
            default -> GL11.glIsEnabled(cap);
        };
    }

    public static boolean glGetBoolean(int pname) {
        if(shouldBypassCache()) {
            return GL11.glGetBoolean(pname);
        }
        return switch (pname) {
            case GL11.GL_ALPHA_TEST -> alphaTest.isEnabled();
            case GL11.GL_BLEND -> blendMode.isEnabled();
            case GL11.GL_CULL_FACE -> cullState.isEnabled();
            case GL11.GL_DEPTH_TEST -> depthTest.isEnabled();
            case GL11.GL_DEPTH_WRITEMASK -> depthState.isEnabled();
            case GL11.GL_FOG -> fogMode.isEnabled();
            case GL11.GL_LIGHTING -> lightingState.isEnabled();
            case GL11.GL_LIGHT0 -> lightStates[0].isEnabled();
            case GL11.GL_LIGHT1 -> lightStates[1].isEnabled();
            case GL11.GL_LIGHT2 -> lightStates[2].isEnabled();
            case GL11.GL_LIGHT3 -> lightStates[3].isEnabled();
            case GL11.GL_LIGHT4 -> lightStates[4].isEnabled();
            case GL11.GL_LIGHT5 -> lightStates[5].isEnabled();
            case GL11.GL_LIGHT6 -> lightStates[6].isEnabled();
            case GL11.GL_LIGHT7 -> lightStates[7].isEnabled();
            case GL11.GL_COLOR_MATERIAL -> colorMaterial.isEnabled();
            case GL11.GL_SCISSOR_TEST -> scissorTest.isEnabled();
            case GL11.GL_TEXTURE_2D -> textures.getTextureUnitStates(activeTextureUnit.getValue()).isEnabled();
            case GL12.GL_RESCALE_NORMAL -> rescaleNormalState.isEnabled();
            default -> GL11.glGetBoolean(pname);
        };
    }

    public static void glGetBoolean(int pname, ByteBuffer params) {
        if(shouldBypassCache()) {
            GL11.glGetBoolean(pname, params);
            return;
        }

        switch (pname) {
            case GL11.GL_COLOR_WRITEMASK -> {
                params.put((byte) (colorMask.red ? GL11.GL_TRUE : GL11.GL_FALSE));
                params.put((byte) (colorMask.green ? GL11.GL_TRUE : GL11.GL_FALSE));
                params.put((byte) (colorMask.blue ? GL11.GL_TRUE : GL11.GL_FALSE));
                params.put((byte) (colorMask.alpha ? GL11.GL_TRUE : GL11.GL_FALSE));
            }
            default -> {
                if(!HAS_MULTIPLE_SET.contains(pname)) {
                    params.put(0, (byte) (glGetBoolean(pname) ? GL11.GL_TRUE : GL11.GL_FALSE));
                } else {
                    GL11.glGetBoolean(pname, params);
                }
            }
        }
    }

    @SneakyThrows
    public static int getMatrixStackDepth(Matrix4fStack stack) {
        return (int) MAT4_STACK_CURR_DEPTH.invokeExact(stack);
    }

    public static int glGetInteger(int pname) {
        if(shouldBypassCache()) {
            return GL11.glGetInteger(pname);
        }

        return switch (pname) {
            case GL11.GL_ALPHA_TEST_FUNC -> alphaState.getFunction();
            case GL11.GL_DEPTH_FUNC -> depthState.getFunc();
            case GL11.GL_LIST_MODE -> glListMode;
            case GL11.GL_MATRIX_MODE -> matrixMode.getMode();
            case GL11.GL_SHADE_MODEL -> shadeModelState.getValue();
            case GL11.GL_TEXTURE_BINDING_2D -> getBoundTexture();
            case GL14.GL_BLEND_DST_ALPHA -> blendState.getDstAlpha();
            case GL14.GL_BLEND_DST_RGB -> blendState.getDstRgb();
            case GL14.GL_BLEND_SRC_ALPHA -> blendState.getSrcAlpha();
            case GL14.GL_BLEND_SRC_RGB -> blendState.getSrcRgb();
            case GL11.GL_COLOR_MATERIAL_FACE -> colorMaterialFace.getValue();
            case GL11.GL_COLOR_MATERIAL_PARAMETER -> colorMaterialParameter.getValue();
            case GL20.GL_CURRENT_PROGRAM -> activeProgram;
            case GL11.GL_MODELVIEW_STACK_DEPTH -> getMatrixStackDepth(modelViewMatrix);
            case GL11.GL_PROJECTION_STACK_DEPTH -> getMatrixStackDepth(projectionMatrix);

            default -> GL11.glGetInteger(pname);
        };
    }

    public static void glGetInteger(int pname, IntBuffer params) {
        if(shouldBypassCache()) {
            GL11.glGetInteger(pname, params);
            return;
        }

        switch (pname) {
            case GL11.GL_VIEWPORT -> viewportState.get(params);
            default -> {
                if(!HAS_MULTIPLE_SET.contains(pname)) {
                    params.put(0, glGetInteger(pname));
                } else {
                    GL11.glGetInteger(pname, params);
                }
            }
        }
    }

    public static void glGetMaterial(int face, int pname, FloatBuffer params) {
        if (shouldBypassCache()) {
            GL11.glGetMaterial(face, pname, params);
            return;
        }

        MaterialStateStack state;
        if (face == GL11.GL_FRONT) {
            state = frontMaterial;
        } else  if (face == GL11.GL_BACK) {
            state = backMaterial;
        } else {
            throw new RuntimeException("Invalid face parameter specified to glGetMaterial: " + face);
        }

        switch (pname) {
            case GL11.GL_AMBIENT -> state.ambient.get(0, params);
            case GL11.GL_DIFFUSE -> state.diffuse.get(0, params);
            case GL11.GL_SPECULAR -> state.specular.get(0, params);
            case GL11.GL_EMISSION -> state.emission.get(0, params);
            case GL11.GL_SHININESS -> params.put(state.shininess);
            case GL11.GL_COLOR_INDEXES -> state.colorIndexes.get(0, params);
            default -> GL11.glGetMaterial(face, pname, params);
        }
    }

    public static void glGetLight(int light, int pname, FloatBuffer params) {
        if (shouldBypassCache()) {
            GL11.glGetLight(light, pname, params);
            return;
        }

        LightStateStack state = lightDataStates[light - GL11.GL_LIGHT0];
        switch (pname) {
            case GL11.GL_AMBIENT -> state.ambient.get(0, params);
            case GL11.GL_DIFFUSE -> state.diffuse.get(0, params);
            case GL11.GL_SPECULAR -> state.specular.get(0, params);
            case GL11.GL_POSITION -> state.position.get(0, params);
            case GL11.GL_SPOT_DIRECTION -> state.spotDirection.get(0, params);
            case GL11.GL_SPOT_EXPONENT -> params.put(state.spotExponent);
            case GL11.GL_SPOT_CUTOFF -> params.put(state.spotCutoff);
            case GL11.GL_CONSTANT_ATTENUATION -> params.put(state.constantAttenuation);
            case GL11.GL_LINEAR_ATTENUATION -> params.put(state.linearAttenuation);
            case GL11.GL_QUADRATIC_ATTENUATION -> params.put(state.quadraticAttenuation);
            default -> GL11.glGetLight(light, pname, params);
        }
    }

    public static void glGetFloat(int pname, FloatBuffer params) {
        if(shouldBypassCache()) {
            GL11.glGetFloat(pname, params);
            return;
        }

        switch (pname) {
            case GL11.GL_MODELVIEW_MATRIX -> modelViewMatrix.get(0, params);
            case GL11.GL_PROJECTION_MATRIX -> projectionMatrix.get(0, params);
//            case GL11.GL_TEXTURE_MATRIX -> textures.getTextureUnitMatrix(getActiveTextureUnit()).get(0, params);
            case GL11.GL_COLOR_CLEAR_VALUE -> clearColor.get(params);
            case GL11.GL_CURRENT_COLOR -> color.get(params);
            default -> {
                if(!HAS_MULTIPLE_SET.contains(pname)) {
                    params.put(0, glGetFloat(pname));
                } else {
                    GL11.glGetFloat(pname, params);
                }
            }
        }
    }

    public static float glGetFloat(int pname) {
        return GL11.glGetFloat(pname);
    }

    // GLStateManager Functions

    public static void glBlendColor(float red, float green, float blue, float alpha) {
        GL14.glBlendColor(red, green, blue, alpha);
    }

    public static void enableBlend() {
        if (AngelicaConfig.enableIris) {
            if (BlendModeStorage.isBlendLocked()) {
                BlendModeStorage.deferBlendModeToggle(true);
                return;
            }
        }
        blendMode.enable();
    }

    public static void disableBlend() {
        if (AngelicaConfig.enableIris) {
            if (BlendModeStorage.isBlendLocked()) {
                BlendModeStorage.deferBlendModeToggle(false);
                return;
            }
        }
        blendMode.disable();
    }

    public static void enableScissorTest() {
        scissorTest.enable();
    }

    public static void disableScissorTest() {
        scissorTest.disable();
    }

    public static void glBlendFunc(int srcFactor, int dstFactor) {
        if (AngelicaConfig.enableIris) {
            if (BlendModeStorage.isBlendLocked()) {
                BlendModeStorage.deferBlendFunc(srcFactor, dstFactor, srcFactor, dstFactor);
                return;
            }
        }
        if (HUDCaching.renderingCacheOverride) {
            blendState.setSrcRgb(srcFactor);
            blendState.setDstRgb(dstFactor);
            blendState.setSrcAlpha(GL11.GL_ONE);
            blendState.setDstAlpha(GL11.GL_ONE_MINUS_SRC_ALPHA);
            OpenGlHelper.glBlendFunc(srcFactor, dstFactor, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            return;
        }
        if (shouldBypassCache() || blendState.getSrcRgb() != srcFactor || blendState.getDstRgb() != dstFactor) {
            blendState.setSrcRgb(srcFactor);
            blendState.setDstRgb(dstFactor);
            GL11.glBlendFunc(srcFactor, dstFactor);
        }

        // Iris
        if (blendFuncListener != null) blendFuncListener.run();
    }

    public static void glBlendEquation(int mode) {
        GL14.glBlendEquation(mode);
    }

    public static void glBlendEquationSeparate(int modeRGB, int modeAlpha) {
        GL20.glBlendEquationSeparate(modeRGB, modeAlpha);
    }

    public static void tryBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (AngelicaConfig.enableIris) {
            if (BlendModeStorage.isBlendLocked()) {
                BlendModeStorage.deferBlendFunc(srcRgb, dstRgb, srcAlpha, dstAlpha);
                return;
            }
        }
        if (HUDCaching.renderingCacheOverride && dstAlpha != GL11.GL_ONE_MINUS_SRC_ALPHA) {
            srcAlpha = GL11.GL_ONE;
            dstAlpha = GL11.GL_ONE_MINUS_SRC_ALPHA;
        }
        if (shouldBypassCache() || blendState.getSrcRgb() != srcRgb || blendState.getDstRgb() != dstRgb || blendState.getSrcAlpha()
            != srcAlpha || blendState.getDstAlpha() != dstAlpha) {
            blendState.setSrcRgb(srcRgb);
            blendState.setDstRgb(dstRgb);
            blendState.setSrcAlpha(srcAlpha);
            blendState.setDstAlpha(dstAlpha);
            OpenGlHelper.glBlendFunc(srcRgb, dstRgb, srcAlpha, dstAlpha);
        }

        // Iris
        if (blendFuncListener != null) blendFuncListener.run();
    }

    public static void glNormal3b(byte nx, byte ny, byte nz) {
        GL11.glNormal3b(nx, ny, nz);
    }
    public static void glNormal3d(double nx, double ny, double nz) {
        GL11.glNormal3d(nx, ny, nz);
    }
    public static void glNormal3f(float nx, float ny, float nz) {
        GL11.glNormal3f(nx, ny, nz);
    }
    public static void glNormal3i(int nx, int ny, int nz) {
        GL11.glNormal3i(nx, ny, nz);
    }

    public static void glDepthFunc(int func) {
        if (shouldBypassCache() || func != depthState.getFunc() ) {
            depthState.setFunc(func);
            GL11.glDepthFunc(func);
        }
    }

    public static void glDepthMask(boolean mask) {
        if (AngelicaConfig.enableIris) {
            if (DepthColorStorage.isDepthColorLocked()) {
                DepthColorStorage.deferDepthEnable(mask);
                return;
            }
        }

        if (shouldBypassCache() || mask != depthState.isEnabled()) {
            depthState.setEnabled(mask);
            GL11.glDepthMask(mask);
        }
    }

    public static void glEdgeFlag(boolean flag) {
        GL11.glEdgeFlag(flag);
    }

    public static void glColor4f(float red, float green, float blue, float alpha) {
        if (changeColor(red, green, blue, alpha)) {
            GL11.glColor4f(red, green, blue, alpha);
        }
    }

    public static void glColor4d(double red, double green, double blue, double alpha) {
        if (changeColor((float) red, (float) green, (float) blue, (float) alpha)) {
            GL11.glColor4d(red, green, blue, alpha);
        }
    }

    public static void glColor4b(byte red, byte green, byte blue, byte alpha) {
        if (changeColor(b2f(red), b2f(green), b2f(blue), b2f(alpha))) {
            GL11.glColor4b(red, green, blue, alpha);
        }
    }

    public static void glColor4ub(byte red, byte green, byte blue, byte alpha) {
        if (changeColor(ub2f(red), ub2f(green), ub2f(blue), ub2f(alpha))) {
            GL11.glColor4ub(red, green, blue, alpha);
        }
    }

    public static void glColor3f(float red, float green, float blue) {
        if (changeColor(red, green, blue, 1.0F)) {
            GL11.glColor3f(red, green, blue);
        }
    }

    public static void glColor3d(double red, double green, double blue) {
        if (changeColor((float) red, (float) green, (float) blue, 1.0F)) {
            GL11.glColor3d(red, green, blue);
        }
    }

    public static void glColor3b(byte red, byte green, byte blue) {
        if (changeColor(b2f(red), b2f(green), b2f(blue), 1.0F)) {
            GL11.glColor3b(red, green, blue);
        }
    }

    public static void glColor3ub(byte red, byte green, byte blue) {
        if (changeColor(ub2f(red), ub2f(green), ub2f(blue), 1.0F)) {
            GL11.glColor3ub(red, green, blue);
        }
    }

    private static float ub2f(byte b) {
        return (b & 0xFF) / 255.0F;
    }

    private static float b2f(byte b) {
        return ((b - Byte.MIN_VALUE) & 0xFF) / 255.0F;
    }

    public static float i2f(int i) { return ((i - Integer.MIN_VALUE) & 0xFFFFFF) / 4294967295.0F; }

    private static boolean changeColor(float red, float green, float blue, float alpha) {
        // Helper function for glColor*
        if (shouldBypassCache() || red != color.getRed() || green != color.getGreen() || blue != color.getBlue() || alpha != color.getAlpha()) {
            color.setRed(red);
            color.setGreen(green);
            color.setBlue(blue);
            color.setAlpha(alpha);
            return true;
        }
        return false;
    }

    private static final Color4 DirtyColor = new Color4(-1.0F, -1.0F, -1.0F, -1.0F);
    public static void clearCurrentColor() {
        // Marks the cache dirty, doesn't actually reset the color
        color.set(DirtyColor);
    }

    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        if (AngelicaConfig.enableIris) {
            if (DepthColorStorage.isDepthColorLocked()) {
                DepthColorStorage.deferColorMask(red, green, blue, alpha);
                return;
            }
        }
        if (shouldBypassCache() || red != colorMask.red || green != colorMask.green || blue != colorMask.blue || alpha != colorMask.alpha) {
            colorMask.red = red;
            colorMask.green = green;
            colorMask.blue = blue;
            colorMask.alpha = alpha;
            GL11.glColorMask(red, green, blue, alpha);
        }
    }

    // Clear Color
    public static void glClearColor(float red, float green, float blue, float alpha) {
        if (shouldBypassCache() || red != clearColor.getRed() || green != clearColor.getGreen() || blue != clearColor.getBlue() || alpha != clearColor.getAlpha()) {
            clearColor.setRed(red);
            clearColor.setGreen(green);
            clearColor.setBlue(blue);
            clearColor.setAlpha(alpha);
            GL11.glClearColor(red, green, blue, alpha);
        }
    }

    public static void glClearDepth(double depth) {
        GL11.glClearDepth(depth);
    }

    // ALPHA
    public static void enableAlphaTest() {
        if (AngelicaConfig.enableIris) {
            if (AlphaTestStorage.isAlphaTestLocked()) {
                AlphaTestStorage.deferAlphaTestToggle(true);
                return;
            }
        }
        alphaTest.enable();
    }

    public static void disableAlphaTest() {
        if (AngelicaConfig.enableIris) {
            if (AlphaTestStorage.isAlphaTestLocked()) {
                AlphaTestStorage.deferAlphaTestToggle(false);
                return;
            }
        }
        alphaTest.disable();
    }

    public static void glAlphaFunc(int function, float reference) {
        if (AngelicaConfig.enableIris) {
            if (AlphaTestStorage.isAlphaTestLocked()) {
                AlphaTestStorage.deferAlphaFunc(function, reference);
                return;
            }
        }
        alphaState.setFunction(function);
        alphaState.setReference(reference);
        GL11.glAlphaFunc(function, reference);
    }

    // Textures
    public static void glActiveTexture(int texture) {
        final int newTexture = texture - GL13.GL_TEXTURE0;
        if (shouldBypassCache() || getActiveTextureUnit() != newTexture) {
            activeTextureUnit.setValue(newTexture);
            GL13.glActiveTexture(texture);
        }
    }

    public static void glActiveTextureARB(int texture) {
        final int newTexture = texture - GL13.GL_TEXTURE0;
        if (shouldBypassCache() || getActiveTextureUnit() != newTexture) {
            activeTextureUnit.setValue(newTexture);
            ARBMultitexture.glActiveTextureARB(texture);
        }
    }

    public static int getBoundTexture() {
        return getBoundTexture(activeTextureUnit.getValue());
    }

    public static int getBoundTexture(int unit) {
        return textures.getTextureUnitBindings(unit).getBinding();
    }

    public static void glBindTexture(int target, int texture) {
        if(target != GL11.GL_TEXTURE_2D) {
            // We're only supporting 2D textures for now
            GL11.glBindTexture(target, texture);
            return;
        }

        final TextureBinding textureUnit = textures.getTextureUnitBindings(GLStateManager.activeTextureUnit.getValue());

        if (shouldBypassCache() || textureUnit.getBinding() != texture) {
            GL11.glBindTexture(target, texture);
            textureUnit.setBinding(texture);
            TextureTracker.INSTANCE.onBindTexture(texture);
        }
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) {
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, FloatBuffer pixels) {
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, DoubleBuffer pixels) {
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels != null ? pixels.asIntBuffer() : (IntBuffer) null);
        GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, long pixels_buffer_offset) {
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels_buffer_offset);
        GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels_buffer_offset);
    }

    public static void glTexCoord1f(float s) {
        GL11.glTexCoord1f(s);
    }
    public static void glTexCoord1d(double s) {
        GL11.glTexCoord1d(s);
    }
    public static void glTexCoord2f(float s, float t) {
        GL11.glTexCoord2f(s, t);
    }
    public static void glTexCoord2d(double s, double t) {
        GL11.glTexCoord2d(s, t);
    }
    public static void glTexCoord3f(float s, float t, float r) {
        GL11.glTexCoord3f(s, t, r);
    }
    public static void glTexCoord3d(double s, double t, double r) {
        GL11.glTexCoord3d(s, t, r);
    }
    public static void glTexCoord4f(float s, float t, float r, float q) {
        GL11.glTexCoord4f(s, t, r, q);
    }
    public static void glTexCoord4d(double s, double t, double r, double q) {
        GL11.glTexCoord4d(s, t, r, q);
    }

    public static void glDeleteTextures(int id) {
        onDeleteTexture(id);

        GL11.glDeleteTextures(id);
    }

    public static void glDeleteTextures(IntBuffer ids) {
        for(int i = 0; i < ids.remaining(); i++) {
            onDeleteTexture(ids.get(i));
        }

        GL11.glDeleteTextures(ids);
    }

    public static void enableTexture() {
        final int textureUnit = getActiveTextureUnit();
        if (AngelicaConfig.enableIris) {
            // Iris
            boolean updatePipeline = false;
            if (textureUnit == IrisSamplers.ALBEDO_TEXTURE_UNIT) {
                StateTracker.INSTANCE.albedoSampler = true;
                updatePipeline = true;
            } else if (textureUnit == IrisSamplers.LIGHTMAP_TEXTURE_UNIT) {
                StateTracker.INSTANCE.lightmapSampler = true;
                updatePipeline = true;
            }

            if (updatePipeline) {
                Iris.getPipelineManager().getPipeline().ifPresent(p -> p.setInputs(StateTracker.INSTANCE.getInputs()));
            }
        }
        textures.getTextureUnitStates(textureUnit).enable();
    }

    public static void disableTexture() {
        final int textureUnit = getActiveTextureUnit();
        if (AngelicaConfig.enableIris) {
            // Iris
            boolean updatePipeline = false;
            if (textureUnit == IrisSamplers.ALBEDO_TEXTURE_UNIT) {
                StateTracker.INSTANCE.albedoSampler = false;
                updatePipeline = true;
            } else if (textureUnit == IrisSamplers.LIGHTMAP_TEXTURE_UNIT) {
                StateTracker.INSTANCE.lightmapSampler = false;
                updatePipeline = true;
            }

            if (updatePipeline) {
                Iris.getPipelineManager().getPipeline().ifPresent(p -> p.setInputs(StateTracker.INSTANCE.getInputs()));
            }
        }
        textures.getTextureUnitStates(textureUnit).disable();
    }

    public static void glRasterPos2f(float x, float y) {
        GL11.glRasterPos2f(x, y);
    }
    public static void glRasterPos2d(double x, double y) {
        GL11.glRasterPos2d(x, y);
    }
    public static void glRasterPos2i(int x, int y) {
        GL11.glRasterPos2i(x, y);
    }
    public static void glRasterPos3f(float x, float y, float z) {
        GL11.glRasterPos3f(x, y, z);
    }
    public static void glRasterPos3d(double x, double y, double z) {
        GL11.glRasterPos3d(x, y, z);
    }
    public static void glRasterPos3i(int x, int y, int z) {
        GL11.glRasterPos3i(x, y, z);
    }
    public static void glRasterPos4f(float x, float y, float z, float w) {
        GL11.glRasterPos4f(x, y, z, w);
    }
    public static void glRasterPos4d(double x, double y, double z, double w) {
        GL11.glRasterPos4d(x, y, z, w);
    }
    public static void glRasterPos4i(int x, int y, int z, int w) {
        GL11.glRasterPos4i(x, y, z, w);
    }


    public static void setFilter(boolean bilinear, boolean mipmap) {
        int i, j;
        if (bilinear) {
            i = mipmap ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR;
            j = GL11.GL_LINEAR;
        } else {
            i = mipmap ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST;
            j = GL11.GL_NEAREST;
        }
        glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, i);
        glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, j);
    }

    public static void trySyncProgram() {
        if (AngelicaConfig.enableIris) {
            Iris.getPipelineManager().getPipeline().ifPresent(WorldRenderingPipeline::syncProgram);
        }
    }

    public static void glBegin(int mode) {
        trySyncProgram();
        GL11.glBegin(mode);
    }

    public static void glDrawElements(int mode, ByteBuffer indices) {
        trySyncProgram();
        GL11.glDrawElements(mode, indices);
    }

    public static void glDrawElements(int mode, IntBuffer indices) {
        trySyncProgram();
        GL11.glDrawElements(mode, indices);
    }

    public static void glDrawElements(int mode, ShortBuffer indices) {
        trySyncProgram();
        GL11.glDrawElements(mode, indices);
    }

    public static void glDrawElements(int mode, int indices_count, int type, long indices_buffer_offset) {
        trySyncProgram();
        GL11.glDrawElements(mode, indices_count, type, indices_buffer_offset);
    }

    public static void glDrawElements(int mode, int count, int type, ByteBuffer indices) {
        trySyncProgram();
        GL11.glDrawElements(mode, count, type, indices);
    }

    public static void glDrawBuffer(int mode) {
        trySyncProgram();
        GL11.glDrawBuffer(mode);
    }

    public static void glDrawArrays(int mode, int first, int count) {
        trySyncProgram();
        GL11.glDrawArrays(mode, first, count);
    }

    public static void glLogicOp(int opcode) {
        GL11.glLogicOp(opcode);
    }

    public static void defaultBlendFunc() {
        tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
    }

    public static void enableCull() {
        cullState.enable();
    }

    public static void disableCull() {
        cullState.disable();
    }

    public static void enableDepthTest() {
        depthTest.enable();
    }

    public static void disableDepthTest() {
        depthTest.disable();
    }

    public static void enableLighting() {
        lightingState.enable();
    }

    public static void enableLight(int light) {
        lightStates[light].enable();
    }

    public static void enableColorMaterial() {
        colorMaterial.enable();
        float r = getColor().getRed();
        float g = getColor().getGreen();
        float b = getColor().getBlue();
        float a = getColor().getAlpha();
        if (colorMaterialFace.getValue() == GL11.GL_FRONT || colorMaterialFace.getValue() == GL11.GL_FRONT_AND_BACK) {
            switch (colorMaterialParameter.getValue()) {
                case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                    frontMaterial.ambient.set(r, g, b, a);
                    frontMaterial.diffuse.set(r, g, b, a);
                }
                case GL11.GL_AMBIENT -> frontMaterial.ambient.set(r, g, b, a);
                case GL11.GL_DIFFUSE -> frontMaterial.diffuse.set(r, g, b, a);
                case GL11.GL_SPECULAR -> frontMaterial.specular.set(r, g, b, a);
                case GL11.GL_EMISSION -> frontMaterial.emission.set(r, g, b, a);
            }
        }
        if (colorMaterialFace.getValue() == GL11.GL_BACK || colorMaterialFace.getValue() == GL11.GL_FRONT_AND_BACK) {
            switch (colorMaterialParameter.getValue()) {
                case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                    backMaterial.ambient.set(r, g, b, a);
                    backMaterial.diffuse.set(r, g, b, a);
                }
                case GL11.GL_AMBIENT -> backMaterial.ambient.set(r, g, b, a);
                case GL11.GL_DIFFUSE -> backMaterial.diffuse.set(r, g, b, a);
                case GL11.GL_SPECULAR -> backMaterial.specular.set(r, g, b, a);
                case GL11.GL_EMISSION -> backMaterial.emission.set(r, g, b, a);
            }
        }
    }

    public static void disableColorMaterial() {
        colorMaterial.disable();
    }

    public static void disableLighting() {
        lightingState.disable();
    }

    public static void disableLight(int light) {
        lightStates[light].disable();
    }

    public static void enableRescaleNormal() {
        rescaleNormalState.enable();
    }

    public static void disableRescaleNormal() {
        rescaleNormalState.disable();
    }

    public static void enableFog() {
        fogMode.enable();
        if (fogToggleListener != null) {
            fogToggleListener.run();
        }
    }

    public static void disableFog() {
        fogMode.disable();
        if (fogToggleListener != null) {
            fogToggleListener.run();
        }
    }

    public static void glFog(int pname, FloatBuffer param) {
        // TODO: Iris Notifier
        if (HAS_MULTIPLE_SET.contains(pname)) {
            GL11.glFog(pname, param);
            if (pname == GL11.GL_FOG_COLOR) {
                final float red = param.get(0);
                final float green = param.get(1);
                final float blue = param.get(2);

                fogState.getFogColor().set(red, green, blue);
                fogState.setFogAlpha(param.get(3));
                fogState.getFogColorBuffer().clear();
                fogState.getFogColorBuffer().put((FloatBuffer) param.position(0)).flip();
            }
        } else {
            GLStateManager.glFogf(pname, param.get(0));
        }
    }

    public static Vector3d getFogColor() {
        return fogState.getFogColor();
    }

    public static void fogColor(float red, float green, float blue, float alpha) {
        if (shouldBypassCache() || red != fogState.getFogColor().x || green != fogState.getFogColor().y || blue != fogState.getFogColor().z || alpha != fogState.getFogAlpha()) {
            fogState.getFogColor().set(red, green, blue);
            fogState.setFogAlpha(alpha);
            fogState.getFogColorBuffer().clear();
            fogState.getFogColorBuffer().put(red).put(green).put(blue).put(alpha).flip();
            GL11.glFog(GL11.GL_FOG_COLOR, fogState.getFogColorBuffer());
        }
    }

    public static void glFogf(int pname, float param) {
        GL11.glFogf(pname, param);
        // Note: Does not handle GL_FOG_INDEX
        switch (pname) {
            case GL11.GL_FOG_DENSITY -> {
                fogState.setDensity(param);
                if (fogDensityListener != null) {
                    fogDensityListener.run();
                }
            }
            case GL11.GL_FOG_START -> {
                fogState.setStart(param);
                if (fogStartListener != null) {
                    fogStartListener.run();
                }
            }
            case GL11.GL_FOG_END -> {
                fogState.setEnd(param);
                if (fogEndListener != null) {
                    fogEndListener.run();
                }
            }
        }
    }

    public static void glFogi(int pname, int param) {
        GL11.glFogi(pname, param);
        if (pname == GL11.GL_FOG_MODE) {
            fogState.setFogMode(param);
            if (fogModeListener != null) {
                fogModeListener.run();
            }
        }
    }

    public static void setFogBlack() {
        glFogf(GL11.GL_FOG_COLOR, 0.0F);
    }

    public static void glShadeModel(int mode) {
        if (shouldBypassCache() || shadeModelState.getValue() != mode) {
            shadeModelState.setValue(mode);
            GL11.glShadeModel(mode);
        }
    }

    // Iris Functions
    private static void onDeleteTexture(int id) {
        TextureTracker.INSTANCE.onDeleteTexture(id);
        TextureInfoCache.INSTANCE.onDeleteTexture(id);
        if (AngelicaConfig.enableIris) {
            PBRTextureManager.INSTANCE.onDeleteTexture(id);
        }

        for(int i = 0; i < GLStateManager.MAX_TEXTURE_UNITS; i++) {
            if(textures.getTextureUnitBindings(i).getBinding() == id) {
                textures.getTextureUnitBindings(i).setBinding(0);
            }
        }
    }

    public static void makeCurrent(Drawable drawable) throws LWJGLException {
        drawable.makeCurrent();
        final Thread currentThread = Thread.currentThread();

        CurrentThread = currentThread;
        LOGGER.info("Current thread: {}", currentThread.getName());
    }

    public static void glNewList(int list, int mode) {
        if(glListMode > 0) {
            glListNesting += 1;
            return;
        }

        glListId = list;
        glListMode = mode;
        GL11.glNewList(list, mode);

        List<IStateStack<?>> stacks = Feature.maskToFeatures(GL11.GL_ALL_ATTRIB_BITS);
        int size = stacks.size();

        for(int i = 0; i < size; i++) {
            IStateStack<?> stack = stacks.get(i);

            // Feature Stack, copy of current feature state
            glListStates.put(stack, (ISettableState<?>) ((ISettableState<?>)stack).copy());
        }

        if(glListMode == GL11.GL_COMPILE) {
            pushState(GL11.GL_ALL_ATTRIB_BITS);
        }
    }

    public static void glEndList() {
        if (glListNesting > 0) {
            glListNesting -= 1;
            return;
        }

        if(glListMode == 0) {
            throw new RuntimeException("glEndList called outside of a display list!");
        }

        final Set<Map.Entry<IStateStack<?>, ISettableState<?>>> changedStates = new ObjectArraySet<>();
        for(Map.Entry<IStateStack<?>, ISettableState<?>> entry : glListStates.entrySet()) {
            // If the current stack state is different than the copy of the state at the start
            if(!((ISettableState<?>)entry.getKey()).sameAs(entry.getValue())) {
                // Then we want to put into the change set the stack and the copy of the state now
                changedStates.add(new AbstractMap.SimpleEntry<>(entry.getKey(), (ISettableState<?>) ((ISettableState<?>) entry.getKey()).copy()));
            }
        }
        if(changedStates.size() != 0) {
            glListChanges.put(glListId, changedStates);
        }
        if(glListMode == GL11.GL_COMPILE) {
            // GL_COMPILE doesn't actually apply the state, just stores it for replay in glCallList, so we'll
            // roll back any changes that we tracked
            popState();
        }
        glListId = -1;
        glListStates.clear();
        glListMode = 0;
        GL11.glEndList();

    }

    public static void glCallList(int list) {
        if(list < 0) {
            VBOManager.get(list).render();
        } else {
            trySyncProgram();
            GL11.glCallList(list);
            if(glListChanges.containsKey(list)) {
                for(Map.Entry<IStateStack<?>, ISettableState<?>> entry : glListChanges.get(list)) {
                    // Set the stack to the cached state at the end of the call list compilation
                    ((ISettableState<?>)entry.getKey()).set(entry.getValue());
                }
            }
        }
    }

    public static void pushState(int mask) {
        attribs.push(mask);

        List<IStateStack<?>> stacks = Feature.maskToFeatures(mask);
        int size = stacks.size();

        for(int i = 0; i < size; i++) {
            stacks.get(i).push();
        }
    }

    public static void popState() {
        final int mask = attribs.popInt();

        List<IStateStack<?>> stacks = Feature.maskToFeatures(mask);
        int size = stacks.size();

        for(int i = 0; i < size; i++) {
            stacks.get(i).pop();
        }
    }

    public static void glClear(int mask) {
        // TODO: Implement
        GL11.glClear(mask);
    }
    public static void glPushAttrib(int mask) {
        pushState(mask);
        GL11.glPushAttrib(mask);
    }

    public static void glPopAttrib() {
        poppingAttributes = true;
        popState();
        GL11.glPopAttrib();
        poppingAttributes = false;
    }

    // Matrix Operations
    public static void glMatrixMode(int mode) {
        matrixMode.setMode(mode);
    }

    public static void glLoadMatrix(FloatBuffer m) {
        getMatrixStack().set(m);
        GL11.glLoadMatrix(m);
    }

    public static void glLoadMatrix(DoubleBuffer m) {
        conversionMatrix4d.set(m);
        getMatrixStack().set(conversionMatrix4d);
        GL11.glLoadMatrix(m);
    }

    public static Matrix4fStack getMatrixStack() {
        switch (matrixMode.getMode()) {
            case GL11.GL_MODELVIEW -> {
                return modelViewMatrix;
            }
            case GL11.GL_PROJECTION -> {
                return projectionMatrix;
            }
            case GL11.GL_TEXTURE -> {
                return textures.getTextureUnitMatrix(getActiveTextureUnit());
            }
            default -> throw new IllegalStateException("Unknown matrix mode: " + matrixMode.getMode());
        }
    }

    public static void glLoadIdentity() {
        GL11.glLoadIdentity();
        getMatrixStack().identity();
    }

    public static void glTranslatef(float x, float y, float z) {
        GL11.glTranslatef(x, y, z);
        getMatrixStack().translate(x, y, z);
    }
    public static void glTranslated(double x, double y, double z) {
        GL11.glTranslated(x, y, z);
        getMatrixStack().translate((float) x, (float) y, (float) z);
    }

    public static void glScalef(float x, float y, float z) {
        GL11.glScalef(x, y, z);
        getMatrixStack().scale(x, y, z);
    }

    public static void glScaled(double x, double y, double z) {
        GL11.glScaled(x, y, z);
        getMatrixStack().scale((float) x, (float) y, (float) z);
    }

    private static final Matrix4f tempMatrix4f = new Matrix4f();
    public static void glMultMatrix(FloatBuffer floatBuffer) {
        GL11.glMultMatrix(floatBuffer);
        tempMatrix4f.set(floatBuffer);
        getMatrixStack().mul(tempMatrix4f);
    }

    public static final Matrix4d conversionMatrix4d = new Matrix4d();
    public static final Matrix4f conversionMatrix4f = new Matrix4f();
    public static void glMultMatrix(DoubleBuffer matrix) {
        GL11.glMultMatrix(matrix);
        conversionMatrix4d.set(matrix);
        conversionMatrix4f.set(conversionMatrix4d);
        getMatrixStack().mul(conversionMatrix4f);
    }

    private static final Vector3f rotation = new Vector3f();
    public static void glRotatef(float angle, float x, float y, float z) {
        GL11.glRotatef(angle, x, y, z);
        rotation.set(x, y, z).normalize();
        getMatrixStack().rotate((float)Math.toRadians(angle), rotation);
    }

    public static void glRotated(double angle, double x, double y, double z) {
        GL11.glRotated(angle, x, y, z);
        rotation.set(x, y, z).normalize();
        getMatrixStack().rotate((float)Math.toRadians(angle), rotation);
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        GL11.glOrtho(left, right, bottom, top, zNear, zFar);
        getMatrixStack().ortho((float)left, (float)right, (float)bottom, (float)top, (float)zNear, (float)zFar);
    }

    public static void glFrustum(double left, double right, double bottom, double top, double zNear, double zFar) {
        GL11.glFrustum(left, right, bottom, top, zNear, zFar);
        getMatrixStack().frustum((float)left, (float)right, (float)bottom, (float)top, (float)zNear, (float)zFar);
    }
    public static void glPushMatrix() {
        GL11.glPushMatrix();
        try {
            getMatrixStack().pushMatrix();
        } catch(IllegalStateException ignored) {
            // Ignore
            if(AngelicaMod.lwjglDebug)
                AngelicaTweaker.LOGGER.warn("Matrix stack overflow ", new Throwable());
        }
    }

    public static void glPopMatrix() {
        GL11.glPopMatrix();
        try {
            getMatrixStack().popMatrix();
        } catch(IllegalStateException ignored) {
            // Ignore
            if(AngelicaMod.lwjglDebug)
                AngelicaTweaker.LOGGER.warn("Matrix stack underflow ", new Throwable());
        }
    }

    private static final Matrix4f perspectiveMatrix = new Matrix4f();
    private static final FloatBuffer perspectiveBuffer = BufferUtils.createFloatBuffer(16);
    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) {
        perspectiveMatrix.identity().perspective((float)Math.toRadians(fovy), aspect, zNear, zFar);

        perspectiveMatrix.get(0, perspectiveBuffer);
        GL11.glMultMatrix(perspectiveBuffer);

        getMatrixStack().mul(perspectiveMatrix);

    }

    public static void glViewport(int x, int y, int width, int height) {
        GL11.glViewport(x, y, width, height);
        viewportState.setViewPort(x, y, width, height);
    }

    public static int getActiveTextureUnit() {
        return activeTextureUnit.getValue();
    }

    public static int getListMode() {
        return glListMode;
    }


    public static boolean updateTexParameteriCache(int target, int texture, int pname, int param) {
        if (target != GL11.GL_TEXTURE_2D) {
            return true;
        }
        final TextureInfo info = TextureInfoCache.INSTANCE.getInfo(texture);
        if (info == null) {
            return true;
        }
        switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> {
                if(info.getMinFilter() == param && !shouldBypassCache()) return false;
                info.setMinFilter(param);
            }
            case GL11.GL_TEXTURE_MAG_FILTER -> {
                if(info.getMagFilter() == param && !shouldBypassCache()) return false;
                info.setMagFilter(param);
            }
            case GL11.GL_TEXTURE_WRAP_S -> {
                if(info.getWrapS() == param && !shouldBypassCache()) return false;
                info.setWrapS(param);
            }
            case GL11.GL_TEXTURE_WRAP_T -> {
                if(info.getWrapT() == param && !shouldBypassCache()) return false;
                info.setWrapT(param);
            }
            case GL12.GL_TEXTURE_MAX_LEVEL -> {
                if(info.getMaxLevel() == param && !shouldBypassCache()) return false;
                info.setMaxLevel(param);
            }
            case GL12.GL_TEXTURE_MIN_LOD -> {
                if(info.getMinLod() == param && !shouldBypassCache()) return false;
                info.setMinLod(param);
            }
            case GL12.GL_TEXTURE_MAX_LOD -> {
                if(info.getMaxLod() == param && !shouldBypassCache()) return false;
                info.setMaxLod(param);
            }
        }
        return true;
    }


    public static void glTexParameter(int target, int pname, IntBuffer params) {
        if (target != GL11.GL_TEXTURE_2D || params.remaining() != 1 ) {
            GL11.glTexParameter(target, pname, params);
            return;
        }
        if(!updateTexParameteriCache(target, getBoundTexture(), pname, params.get(0))) return;

        GL11.glTexParameter(target, pname, params);
    }

    public static void glTexParameter(int target, int pname, FloatBuffer params) {
        if (target != GL11.GL_TEXTURE_2D || params.remaining() != 1 ) {
            GL11.glTexParameter(target, pname, params);
            return;
        }
        if(!updateTexParameterfCache(target, getBoundTexture(), pname, params.get(0))) return;

        GL11.glTexParameter(target, pname, params);
    }


    public static void glTexParameteri(int target, int pname, int param) {
        if (target != GL11.GL_TEXTURE_2D) {
            GL11.glTexParameteri(target, pname, param);
            return;
        }
        if(!updateTexParameteriCache(target, getBoundTexture(), pname, param)) return;

        GL11.glTexParameteri(target, pname, param);
    }


    public static boolean updateTexParameterfCache(int target, int texture, int pname, float param) {
        if (target != GL11.GL_TEXTURE_2D) {
            return true;
        }
        final TextureInfo info = TextureInfoCache.INSTANCE.getInfo(texture);
        if (info == null) {
            return true;
        }
        switch (pname) {
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> {
                if(info.getMaxAnisotropy() == param && !shouldBypassCache()) return false;
                info.setMaxAnisotropy(param);
            }
            case GL14.GL_TEXTURE_LOD_BIAS -> {
                if(info.getLodBias() == param && !shouldBypassCache()) return false;
                info.setLodBias(param);
            }
        }
        return true;
    }

    public static void glTexParameterf(int target, int pname, float param) {
        if (target != GL11.GL_TEXTURE_2D) {
            GL11.glTexParameterf(target, pname, param);
            return;
        }
        if(!updateTexParameterfCache(getActiveTextureUnit(), target, pname, param)) return;

        GL11.glTexParameterf(target, pname, param);
    }

    public static int getTexParameterOrDefault(int texture, int pname, IntSupplier defaultSupplier) {
        final TextureInfo info = TextureInfoCache.INSTANCE.getInfo(texture);
        if (info == null) {
            return defaultSupplier.getAsInt();
        }
        return switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> info.getMinFilter();
            case GL11.GL_TEXTURE_MAG_FILTER -> info.getMagFilter();
            case GL11.GL_TEXTURE_WRAP_S -> info.getWrapS();
            case GL11.GL_TEXTURE_WRAP_T -> info.getWrapT();
            case GL12.GL_TEXTURE_MAX_LEVEL -> info.getMaxLevel();
            case GL12.GL_TEXTURE_MIN_LOD -> info.getMinLod();
            case GL12.GL_TEXTURE_MAX_LOD -> info.getMaxLod();
            default -> defaultSupplier.getAsInt();
        };
    }
    public static int glGetTexParameteri(int target, int pname) {
        if (target != GL11.GL_TEXTURE_2D || shouldBypassCache()) {
            return GL11.glGetTexParameteri(target, pname);
        }
        return getTexParameterOrDefault(getBoundTexture(), pname, () -> GL11.glGetTexParameteri(target, pname));
    }

    public static float glGetTexParameterf(int target, int pname) {
        if (target != GL11.GL_TEXTURE_2D || shouldBypassCache()) {
            return GL11.glGetTexParameterf(target, pname);
        }
        final TextureInfo info = TextureInfoCache.INSTANCE.getInfo(getBoundTexture());
        if(info == null) {
            return GL11.glGetTexParameterf(target, pname);
        }

        return switch (pname) {
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> info.getMaxAnisotropy();
            case GL14.GL_TEXTURE_LOD_BIAS -> info.getLodBias();
            default -> GL11.glGetTexParameterf(target, pname);
        };
    }

    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        if (target != GL11.GL_TEXTURE_2D || shouldBypassCache()) {
            return GL11.glGetTexLevelParameteri(target, level, pname);
        }
        final TextureInfo info = TextureInfoCache.INSTANCE.getInfo(getBoundTexture());
        if (info == null) {
            return GL11.glGetTexLevelParameteri(target, level, pname);
        }
        return switch (pname) {
            case GL11.GL_TEXTURE_WIDTH -> info.getWidth();
            case GL11.GL_TEXTURE_HEIGHT -> info.getHeight();
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> info.getInternalFormat();
            default -> GL11.glGetTexLevelParameteri(target, level, pname);
        };
    }

    private static void glMaterialFront(int pname, FloatBuffer params) {
        switch (pname) {
            case GL11.GL_AMBIENT -> frontMaterial.setAmbient(params);
            case GL11.GL_DIFFUSE -> frontMaterial.setDiffuse(params);
            case GL11.GL_SPECULAR -> frontMaterial.setSpecular(params);
            case GL11.GL_EMISSION -> frontMaterial.setEmission(params);
            case GL11.GL_SHININESS -> frontMaterial.setShininess(params);
            case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                frontMaterial.setAmbient(params);
                frontMaterial.setDiffuse(params);
            }
            case GL11.GL_COLOR_INDEXES -> frontMaterial.setColorIndexes(params);
        }
    }

    private static void glMaterialBack(int pname, FloatBuffer params) {
        switch (pname) {
            case GL11.GL_AMBIENT -> backMaterial.setAmbient(params);
            case GL11.GL_DIFFUSE -> backMaterial.setDiffuse(params);
            case GL11.GL_SPECULAR -> backMaterial.setSpecular(params);
            case GL11.GL_EMISSION -> backMaterial.setEmission(params);
            case GL11.GL_SHININESS -> backMaterial.setShininess(params);
            case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                backMaterial.setAmbient(params);
                backMaterial.setDiffuse(params);
            }
            case GL11.GL_COLOR_INDEXES -> backMaterial.setColorIndexes(params);
        }
    }

    private static void glMaterialFront(int pname, IntBuffer params) {
        switch (pname) {
            case GL11.GL_AMBIENT -> frontMaterial.setAmbient(params);
            case GL11.GL_DIFFUSE -> frontMaterial.setDiffuse(params);
            case GL11.GL_SPECULAR -> frontMaterial.setSpecular(params);
            case GL11.GL_EMISSION -> frontMaterial.setEmission(params);
            case GL11.GL_SHININESS -> frontMaterial.setShininess(params);
            case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                frontMaterial.setAmbient(params);
                frontMaterial.setDiffuse(params);
            }
            case GL11.GL_COLOR_INDEXES -> frontMaterial.setColorIndexes(params);
        }
    }

    private static void glMaterialBack(int pname, IntBuffer params) {
        switch (pname) {
            case GL11.GL_AMBIENT -> backMaterial.setAmbient(params);
            case GL11.GL_DIFFUSE -> backMaterial.setDiffuse(params);
            case GL11.GL_SPECULAR -> backMaterial.setSpecular(params);
            case GL11.GL_EMISSION -> backMaterial.setEmission(params);
            case GL11.GL_SHININESS -> backMaterial.setShininess(params);
            case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                backMaterial.setAmbient(params);
                backMaterial.setDiffuse(params);
            }
            case GL11.GL_COLOR_INDEXES -> backMaterial.setColorIndexes(params);
        }
    }

    public static void glMaterial(int face, int pname, FloatBuffer params) {
        if (face == GL11.GL_FRONT) {
            glMaterialFront(pname, params);
        } else if (face == GL11.GL_BACK) {
            glMaterialBack(pname, params);
        } else if (face == GL11.GL_FRONT_AND_BACK) {
            glMaterialFront(pname, params);
            glMaterialBack(pname, params);
        } else {
            throw new RuntimeException("Unsupported face value for glMaterial: " + face);
        }
    }

    public static void glMaterial(int face, int pname, IntBuffer params) {
        if (face == GL11.GL_FRONT) {
            glMaterialFront(pname, params);
        } else if (face == GL11.GL_BACK) {
            glMaterialBack(pname, params);
        } else if (face == GL11.GL_FRONT_AND_BACK) {
            glMaterialFront(pname, params);
            glMaterialBack(pname, params);
        } else {
            throw new RuntimeException("Unsupported face value for glMaterial: " + face);
        }
    }

    public static void glMaterialf(int face, int pname, float val) {
        if (pname != GL11.GL_SHININESS) {
            // it is only valid to call glMaterialf for the GL_SHININESS parameter
            return;
        }

        if (face == GL11.GL_FRONT) {
            frontMaterial.setShininess(val);
        } else if (face == GL11.GL_BACK) {
            backMaterial.setShininess(val);
        } else if (face == GL11.GL_FRONT_AND_BACK) {
            frontMaterial.setShininess(val);
            backMaterial.setShininess(val);
        } else {
            throw new RuntimeException("Unsupported face value for glMaterial: " + face);
        }
    }

    public static void glMateriali(int face, int pname, int val) {
        // This will end up no-opping if pname != GL_SHININESS, it is invalid to call this with another pname
        glMaterialf(face, pname, (float) val);
    }

    public static void glLight(int light, int pname, FloatBuffer params) {
        LightStateStack lightState = lightDataStates[light - GL11.GL_LIGHT0];
        switch (pname) {
            case GL11.GL_AMBIENT -> lightState.setAmbient(params);
            case GL11.GL_DIFFUSE -> lightState.setDiffuse(params);
            case GL11.GL_SPECULAR -> lightState.setSpecular(params);
            case GL11.GL_POSITION -> lightState.setPosition(params);
            case GL11.GL_SPOT_DIRECTION -> lightState.setSpotDirection(params);
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(params);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(params);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(params);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(params);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(params);
            default -> GL11.glLight(light, pname, params);
        }
    }

    public static void glLight(int light, int pname, IntBuffer params) {
        LightStateStack lightState = lightDataStates[light - GL11.GL_LIGHT0];
        switch (pname) {
            case GL11.GL_AMBIENT -> lightState.setAmbient(params);
            case GL11.GL_DIFFUSE -> lightState.setDiffuse(params);
            case GL11.GL_SPECULAR -> lightState.setSpecular(params);
            case GL11.GL_POSITION -> lightState.setPosition(params);
            case GL11.GL_SPOT_DIRECTION -> lightState.setSpotDirection(params);
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(params);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(params);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(params);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(params);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(params);
            default -> GL11.glLight(light, pname, params);
        }
    }

    public static void glLightf(int light, int pname, float param) {
        LightStateStack lightState = lightDataStates[light - GL11.GL_LIGHT0];
        switch (pname) {
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(param);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(param);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(param);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(param);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(param);
            default -> GL11.glLightf(light, pname, param);
        }
    }

    public static void glLighti(int light, int pname, int param) {
        LightStateStack lightState = lightDataStates[light - GL11.GL_LIGHT0];
        switch (pname) {
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(param);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(param);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(param);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(param);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(param);
            default -> GL11.glLighti(light, pname, param);
        }
    }

    public static void glLightModel(int pname, FloatBuffer params) {
        switch (pname) {
            case GL11.GL_LIGHT_MODEL_AMBIENT -> lightModel.setAmbient(params);
            case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> lightModel.setLocalViewer(params);
            case GL11.GL_LIGHT_MODEL_TWO_SIDE -> lightModel.setTwoSide(params);
            default -> GL11.glLightModel(pname, params);
        }
    }
    public static void glLightModel(int pname, IntBuffer params) {
        switch (pname) {
            case GL11.GL_LIGHT_MODEL_AMBIENT -> lightModel.setAmbient(params);
            case GL12.GL_LIGHT_MODEL_COLOR_CONTROL -> lightModel.setColorControl(params);
            case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> lightModel.setLocalViewer(params);
            case GL11.GL_LIGHT_MODEL_TWO_SIDE -> lightModel.setTwoSide(params);
            default -> GL11.glLightModel(pname, params);
        }
    }
    public static void glLightModelf(int pname, float param) {
        switch (pname) {
            case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> lightModel.setLocalViewer(param);
            case GL11.GL_LIGHT_MODEL_TWO_SIDE -> lightModel.setTwoSide(param);
            default -> GL11.glLightModelf(pname, param);
        }
    }
    public static void glLightModeli(int pname, int param) {
        switch (pname) {
            case GL12.GL_LIGHT_MODEL_COLOR_CONTROL -> lightModel.setColorControl(param);
            case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> lightModel.setLocalViewer(param);
            case GL11.GL_LIGHT_MODEL_TWO_SIDE -> lightModel.setTwoSide(param);
            default -> GL11.glLightModeli(pname, param);
        }
    }

    public static void glColorMaterial(int face, int mode) {
        if (shouldBypassCache() || (colorMaterialFace.getValue() != face || colorMaterialParameter.getValue() != mode)) {
            colorMaterialFace.setValue(face);
            colorMaterialParameter.setValue(mode);
            GL11.glColorMaterial(face, mode);
        }
    }

    public static void glDepthRange(double near, double far) {
        GL11.glDepthRange(near, far);
    }

    public static void glUseProgram(int program) {
        if(program != activeProgram || shouldBypassCache()) {
            activeProgram = program;
            if(AngelicaMod.lwjglDebug) {
                final String programName = GLDebug.getObjectLabel(KHRDebug.GL_PROGRAM, program);
                GLDebug.debugMessage("Activating Program - " + program + ":" + programName);
            }
            GL20.glUseProgram(program);
        }
    }



}
