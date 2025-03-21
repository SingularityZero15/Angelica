package com.gtnewhorizons.angelica.transform;

import com.google.common.collect.ImmutableSet;
import com.gtnewhorizon.gtnhlib.asm.ClassConstantPoolParser;
import com.gtnewhorizons.angelica.loading.AngelicaTweaker;
import net.coderbot.iris.IrisLogging;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * This transformer redirects many GL calls to our custom GLStateManager
 */
public class RedirectorTransformer implements IClassTransformer {

    private static final boolean ASSERT_MAIN_THREAD = Boolean.parseBoolean(System.getProperty("angelica.assertMainThread", "false"));
    private static final String Drawable = "org/lwjgl/opengl/Drawable";
    private static final String GLStateManager = "com/gtnewhorizons/angelica/glsm/GLStateManager";
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String GL13 = "org/lwjgl/opengl/GL13";
    private static final String GL14 = "org/lwjgl/opengl/GL14";
    private static final String GL20 = "org/lwjgl/opengl/GL20";
    private static final String Project = "org/lwjgl/util/glu/Project";

    private static final String BlockClass = "net/minecraft/block/Block";
    private static final String BlockPackage = "net/minecraft/block/Block";

    private static final String OpenGlHelper = "net/minecraft/client/renderer/OpenGlHelper";
    private static final String EXTBlendFunc = "org/lwjgl/opengl/EXTBlendFuncSeparate";
    private static final String ARBMultiTexture = "org/lwjgl/opengl/ARBMultitexture";
    private static final String MinecraftClient = "net.minecraft.client";
    private static final String SplashProgress = "cpw.mods.fml.client.SplashProgress";
    private static final String ThreadedBlockData = "com/gtnewhorizons/angelica/glsm/ThreadedBlockData";
    private static final Set<String> ExcludedMinecraftMainThreadChecks = ImmutableSet.of(
        "startGame", "func_71384_a",
        "initializeTextures", "func_77474_a"
    );
    /** All classes in <tt>net.minecraft.block.*</tt> are the block subclasses save for these. */
    private static final List<String> VanillaBlockExclusions = Arrays.asList(
        "net/minecraft/block/IGrowable",
        "net/minecraft/block/ITileEntityProvider",
        "net/minecraft/block/BlockEventData",
        "net/minecraft/block/BlockSourceImpl",
        "net/minecraft/block/material/"
    );

    private static final ClassConstantPoolParser cstPoolParser = new ClassConstantPoolParser(GL11, GL13, GL14, OpenGlHelper, EXTBlendFunc, ARBMultiTexture, BlockPackage, Project);
    private static final Map<String, Map<String, String>> methodRedirects = new HashMap<>();
    private static final Map<Integer, String> glCapRedirects = new HashMap<>();
    private static final List<String> TransformerExclusions = Arrays.asList(
        "org.lwjgl",
        "com.gtnewhorizons.angelica.glsm.",
        "com.gtnewhorizons.angelica.transform",
        "me.eigenraven.lwjgl3ify"
    );

    private static final Set<String> moddedBlockSubclasses = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Block owners we *shouldn't* redirect because they shadow one of our fields
    private static final Set<String> blockOwnerExclusions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Needed because the config is loaded in LaunchClassLoader, but we need to access it in the parent system loader.
    private static final MethodHandle angelicaConfigSodiumEnabledGetter;

    static {
        glCapRedirects.put(org.lwjgl.opengl.GL11.GL_ALPHA_TEST, "AlphaTest");
        glCapRedirects.put(org.lwjgl.opengl.GL11.GL_BLEND, "Blend");
        glCapRedirects.put(org.lwjgl.opengl.GL11.GL_DEPTH_TEST, "DepthTest");
        glCapRedirects.put(org.lwjgl.opengl.GL11.GL_CULL_FACE, "Cull");
        glCapRedirects.put(org.lwjgl.opengl.GL11.GL_LIGHTING, "Lighting");
        glCapRedirects.put(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, "Texture");
        glCapRedirects.put(org.lwjgl.opengl.GL11.GL_FOG, "Fog");
        glCapRedirects.put(org.lwjgl.opengl.GL12.GL_RESCALE_NORMAL, "RescaleNormal");
        glCapRedirects.put(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST, "ScissorTest");
        methodRedirects.put(GL11, RedirectMap.newMap()
            .add("glAlphaFunc")
            .add("glBegin")
            .add("glBindTexture")
            .add("glBlendFunc")
            .add("glCallList")
            .add("glClear")
            .add("glClearColor")
            .add("glClearDepth")
            .add("glColor3b")
            .add("glColor3d")
            .add("glColor3f")
            .add("glColor3ub")
            .add("glColor4b")
            .add("glColor4d")
            .add("glColor4f")
            .add("glColor4ub")
            .add("glColorMask")
            .add("glColorMaterial")
            .add("glDeleteTextures")
            .add("glDepthFunc")
            .add("glDepthMask")
            .add("glDepthRange")
            .add("glDrawArrays")
            .add("glDrawBuffer")
            .add("glDrawElements")
            .add("glEdgeFlag")
            .add("glEndList")
            .add("glFog")
            .add("glFogf")
            .add("glFogi")
            .add("glFrustum")
            .add("glGetBoolean")
            .add("glGetFloat")
            .add("glGetInteger")
            .add("glGetLight")
            .add("glGetMaterial")
            .add("glGetTexLevelParameteri")
            .add("glGetTexParameterf")
            .add("glGetTexParameteri")
            .add("glIsEnabled")
            .add("glMaterial")
            .add("glMaterialf")
            .add("glMateriali")
            .add("glLight")
            .add("glLightf")
            .add("glLighti")
            .add("glLightModel")
            .add("glLightModelf")
            .add("glLightModeli")
            .add("glLoadIdentity")
            .add("glLoadMatrix")
            .add("glLogicOp")
            .add("glMatrixMode")
            .add("glMultMatrix")
            .add("glNewList")
            .add("glNormal3b")
            .add("glNormal3d")
            .add("glNormal3f")
            .add("glNormal3i")
            .add("glOrtho")
            .add("glPopAttrib")
            .add("glPopMatrix")
            .add("glPushAttrib")
            .add("glPushMatrix")
            .add("glRasterPos2d")
            .add("glRasterPos2f")
            .add("glRasterPos2i")
            .add("glRasterPos3d")
            .add("glRasterPos3f")
            .add("glRasterPos3i")
            .add("glRasterPos4d")
            .add("glRasterPos4f")
            .add("glRasterPos4i")
            .add("glRotated")
            .add("glRotatef")
            .add("glScaled")
            .add("glScalef")
            .add("glShadeModel")
            .add("glTexCoord1d")
            .add("glTexCoord1f")
            .add("glTexCoord2d")
            .add("glTexCoord2f")
            .add("glTexCoord3d")
            .add("glTexCoord3f")
            .add("glTexCoord4d")
            .add("glTexCoord4f")
            .add("glTexImage2D")
            .add("glTexParameter")
            .add("glTexParameterf")
            .add("glTexParameteri")
            .add("glTexParameteri")
            .add("glTranslated")
            .add("glTranslatef")
            .add("glViewport")
        );
        methodRedirects.put(GL13, RedirectMap.newMap().add("glActiveTexture"));
        methodRedirects.put(GL14, RedirectMap.newMap()
            .add("glBlendFuncSeparate", "tryBlendFuncSeparate")
            .add("glBlendColor")
            .add("glBlendEquation")
        );
        methodRedirects.put(GL20, RedirectMap.newMap()
            .add("glBlendEquationSeparate")
            .add("glUseProgram")
        );
        methodRedirects.put(OpenGlHelper, RedirectMap.newMap()
            .add("glBlendFunc", "tryBlendFuncSeparate")
            .add("func_148821_a", "tryBlendFuncSeparate"));
        methodRedirects.put(EXTBlendFunc, RedirectMap.newMap().add("glBlendFuncSeparateEXT", "tryBlendFuncSeparate"));
        methodRedirects.put(ARBMultiTexture, RedirectMap.newMap().add("glActiveTextureARB"));
        methodRedirects.put(Project, RedirectMap.newMap().add("gluPerspective"));

        try {
            final Class<?> angelicaConfig = Class.forName("com.gtnewhorizons.angelica.config.AngelicaConfig", true, Launch.classLoader);
            final MethodHandle sodiumGetter = MethodHandles.lookup().findStaticGetter(angelicaConfig, "enableSodium", boolean.class);
            angelicaConfigSodiumEnabledGetter = sodiumGetter;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isSodiumEnabled() {
        try {
            return (boolean) angelicaConfigSodiumEnabledGetter.invokeExact();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isVanillaBlockSubclass(String className) {
        if (className.startsWith(BlockPackage)) {
            for (String exclusion : VanillaBlockExclusions) {
                if (className.startsWith(exclusion)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean isBlockSubclass(String className) {
        return isVanillaBlockSubclass(className) || moddedBlockSubclasses.contains(className);
    }

    public static List<String> getTransformerExclusions() {
        return Collections.unmodifiableList(TransformerExclusions);
    }

    public boolean shouldRfbTransform(byte[] basicClass) {
        return cstPoolParser.find(basicClass, true);
    }

    @Override
    public byte[] transform(final String className, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;

        // Ignore classes that are excluded from transformation - Doesn't fully work without the
        // TransformerExclusions due to some nested classes
        for (String exclusion : TransformerExclusions) {
            if (className.startsWith(exclusion)) {
                return basicClass;
            }
        }

        if (!cstPoolParser.find(basicClass, true)) {
            return basicClass;
        }

        // Keep in sync with com.gtnewhorizons.angelica.loading.rfb.RedirectorTransformerWrapper
        final ClassReader cr = new ClassReader(basicClass);
        final ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        final boolean changed = transformClassNode(transformedName, cn);
        if (changed) {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            final byte[] bytes = cw.toByteArray();
            AngelicaTweaker.dumpClass(transformedName, basicClass, bytes, this);
            return bytes;
        }
        return basicClass;
    }

    /** @return Was the class changed? */
    public boolean transformClassNode(String transformedName, ClassNode cn) {
        if (cn == null) {
            return false;
        }

        // Track subclasses of Block
        if (!isVanillaBlockSubclass(cn.name) && isBlockSubclass(cn.superName)) {
            moddedBlockSubclasses.add(cn.name);
            cstPoolParser.addString(cn.name);
        }

        // Check if this class shadows any fields of the parent class
        if(moddedBlockSubclasses.contains(cn.name)) {
            // If a superclass shadows, then so do we, because JVM will resolve a reference on our class to that
            // superclass
            boolean doWeShadow;
            if(blockOwnerExclusions.contains(cn.superName)) {
                doWeShadow = true;
            } else {
                // Check if we declare any known field names
                Set<String> fieldsDeclaredByClass = cn.fields.stream().map(f -> f.name).collect(Collectors.toSet());
                doWeShadow = BlockTransformer.BlockBoundsFields.stream().anyMatch(pair -> fieldsDeclaredByClass.contains(pair.getLeft()) || fieldsDeclaredByClass.contains(pair.getRight()));
            }
            if (doWeShadow) {
                AngelicaTweaker.LOGGER.info("Class '{}' shadows one or more block bounds fields, these accesses won't be redirected!", cn.name);
                blockOwnerExclusions.add(cn.name);
            }
        }

        boolean changed = false;
        for (MethodNode mn : cn.methods) {
            if (transformedName.equals("net.minecraft.client.renderer.OpenGlHelper") && (mn.name.equals("glBlendFunc") || mn.name.equals("func_148821_a"))) {
                continue;
            }
            boolean redirectInMethod = false;
            for (AbstractInsnNode node : mn.instructions.toArray()) {
                if (node instanceof MethodInsnNode mNode) {
                    if (mNode.owner.equals(GL11) && (mNode.name.equals("glEnable") || mNode.name.equals("glDisable")) && mNode.desc.equals("(I)V")) {
                        final AbstractInsnNode prevNode = node.getPrevious();
                        String name = null;
                        if (prevNode instanceof LdcInsnNode ldcNode) {
                            name = glCapRedirects.get(((Integer) ldcNode.cst));
                        } else if (prevNode instanceof IntInsnNode intNode) {
                            name = glCapRedirects.get(intNode.operand);
                        }
                        if (name != null) {
                            if (mNode.name.equals("glEnable")) {
                                name = "enable" + name;
                            } else {
                                name = "disable" + name;
                            }
                        }
                        if (IrisLogging.ENABLE_SPAM) {
                            if (name == null) {
                                AngelicaTweaker.LOGGER.info("Redirecting call in {} from GL11.{}(I)V to GLStateManager.{}(I)V", transformedName, mNode.name, mNode.name);
                            } else {
                                AngelicaTweaker.LOGGER.info("Redirecting call in {} from GL11.{}(I)V to GLStateManager.{}()V", transformedName, mNode.name, name);
                            }
                        }
                        mNode.owner = GLStateManager;
                        if (name != null) {
                            mNode.name = name;
                            mNode.desc = "()V";
                            mn.instructions.remove(prevNode);
                        }
                        changed = true;
                        redirectInMethod = true;
                    } else if (mNode.owner.startsWith(Drawable) && mNode.name.equals("makeCurrent")) {
                        mNode.setOpcode(Opcodes.INVOKESTATIC);
                        mNode.owner = GLStateManager;
                        mNode.desc = "(L" + Drawable + ";)V";
                        mNode.itf = false;
                        changed = true;
                        if (IrisLogging.ENABLE_SPAM) {
                            AngelicaTweaker.LOGGER.info("Redirecting call in {} to GLStateManager.makeCurrent()", transformedName);
                        }
                    } else {
                        final Map<String, String> redirects = methodRedirects.get(mNode.owner);
                        if (redirects != null && redirects.containsKey(mNode.name)) {
                            if (IrisLogging.ENABLE_SPAM) {
                                final String shortOwner = mNode.owner.substring(mNode.owner.lastIndexOf("/") + 1);
                                AngelicaTweaker.LOGGER.info("Redirecting call in {} from {}.{}{} to GLStateManager.{}{}", transformedName, shortOwner, mNode.name, mNode.desc, redirects.get(mNode.name), mNode.desc);
                            }
                            mNode.owner = GLStateManager;
                            mNode.name = redirects.get(mNode.name);
                            changed = true;
                            redirectInMethod = true;
                        }
                    }
                }
                else if ((node.getOpcode() == Opcodes.GETFIELD || node.getOpcode() == Opcodes.PUTFIELD) && node instanceof FieldInsnNode fNode) {
                    if(!blockOwnerExclusions.contains(fNode.owner) && isBlockSubclass(fNode.owner) && isSodiumEnabled()) {
                        Pair<String, String> fieldToRedirect = null;
                        for(Pair<String, String> blockPairs : BlockTransformer.BlockBoundsFields) {
                            if(fNode.name.equals(blockPairs.getLeft()) || fNode.name.equals(blockPairs.getRight())) {
                                fieldToRedirect = blockPairs;
                                break;
                            }
                        }
                        if (fieldToRedirect != null) {
                            if (IrisLogging.ENABLE_SPAM) {
                                AngelicaTweaker.LOGGER.info("Redirecting Block.{} in {} to thread-safe wrapper", fNode.name, transformedName);
                            }
                            // Perform the redirect
                            fNode.name = fieldToRedirect.getLeft(); // use unobfuscated name
                            fNode.owner = ThreadedBlockData;
                            // Inject getter before the field access, to turn Block -> ThreadedBlockData
                            final MethodInsnNode getter = new MethodInsnNode(Opcodes.INVOKESTATIC, ThreadedBlockData, "get", "(L" + BlockClass + ";)L" + ThreadedBlockData + ";", false);
                            if (node.getOpcode() == Opcodes.GETFIELD) {
                                mn.instructions.insertBefore(fNode, getter);
                            } else if (node.getOpcode() == Opcodes.PUTFIELD) {
                                // FIXME: this code assumes doubles
                                // Stack: Block, double
                                final InsnList beforePut = new InsnList();
                                beforePut.add(new InsnNode(Opcodes.DUP2_X1));
                                // Stack: double, Block, double
                                beforePut.add(new InsnNode(Opcodes.POP2));
                                // Stack: double, Block
                                beforePut.add(getter);
                                // Stack: double, ThreadedBlockData
                                beforePut.add(new InsnNode(Opcodes.DUP_X2));
                                // Stack: ThreadedBlockData, double, ThreadedBlockData
                                beforePut.add(new InsnNode(Opcodes.POP));
                                // Stack: ThreadedBlockData, double
                                mn.instructions.insertBefore(fNode, beforePut);
                            }
                            changed = true;
                        }
                    }
                }
            }
            if (ASSERT_MAIN_THREAD && redirectInMethod && !transformedName.startsWith(SplashProgress) && !(transformedName.startsWith(MinecraftClient) && ExcludedMinecraftMainThreadChecks.contains(mn.name))) {
                mn.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, GLStateManager, "assertMainThread", "()V", false));
            }
        }

        return changed;
    }

    private static class RedirectMap<K> extends HashMap<K, K> {

        private static final long serialVersionUID = 1712218575345511543L;

        public static RedirectMap<String> newMap() {
            return new RedirectMap<>();
        }

        public RedirectMap<K> add(K name) {
            this.put(name, name);
            return this;
        }

        public RedirectMap<K> add(K name, K newName) {
            this.put(name, newName);
            return this;
        }
    }

}
