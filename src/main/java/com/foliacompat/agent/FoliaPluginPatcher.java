package com.foliacompat.agent;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.logging.Logger;

/**
 * Folia 插件加载拦截器。
 *
 * Folia 在 SpigotPluginProviderFactory.build() 中检查 plugin.yml 的 folia-supported 字段，
 * 缺少此字段的插件会被直接拒绝加载。
 *
 * 此转换器拦截 build() 方法，注入对 configuration.setFoliaSupported(true) 的调用，
 * 使所有插件都能通过 Folia 的兼容性检查。
 *
 * 使用纯 MethodVisitor 而非 AdviceAdapter/LocalVariablesSorter，
 * 避免对 EXPAND_FRAMES 的依赖。
 */
public class FoliaPluginPatcher implements ClassFileTransformer {

    private static final Logger LOGGER = Logger.getLogger("FoliaCompat-Agent");

    private static final String SPIGOT_PROVIDER_FACTORY =
            "io/papermc/paper/plugin/provider/type/spigot/SpigotPluginProviderFactory";
    private static final String SPIGOT_PLUGIN_CONFIG =
            "io/papermc/paper/plugin/provider/type/spigot/SpigotPluginConfiguration";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;

        if (SPIGOT_PROVIDER_FACTORY.equals(className)) {
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                ClassVisitor visitor = new PluginPatcherVisitor(writer);
                reader.accept(visitor, ClassReader.EXPAND_FRAMES);
                LOGGER.info("[FoliaCompat-Agent] Patched SpigotPluginProviderFactory to bypass folia-supported check");
                return writer.toByteArray();
            } catch (Exception e) {
                LOGGER.warning("[FoliaCompat-Agent] Failed to patch " + className + ": " + e.getMessage());
            }
        }

        return null;
    }

    private static class PluginPatcherVisitor extends ClassVisitor {

        PluginPatcherVisitor(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            if ("build".equals(name)) {
                return new BuildMethodPatcher(mv);
            }

            return mv;
        }
    }

    /**
     * 在 build() 方法入口处注入 folia-supported 绕过逻辑。
     *
     * 注入的字节码等价于：
     * <pre>
     * try {
     *     ((SpigotPluginConfiguration) arg1).setFoliaSupported(true);
     * } catch (Throwable ignore) {}
     * </pre>
     *
     * 使用纯 MethodVisitor，不依赖 LocalVariablesSorter，
     * 因此不需要 EXPAND_FRAMES。
     */
    private static class BuildMethodPatcher extends MethodVisitor {

        BuildMethodPatcher(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();

            // try { configuration.setFoliaSupported(true) } catch (Throwable ignore) {}
            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label catchHandler = new Label();
            Label afterCatch = new Label();

            visitLabel(tryStart);
            visitVarInsn(Opcodes.ALOAD, 1); // configuration 参数
            visitInsn(Opcodes.ICONST_1);    // true
            visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    SPIGOT_PLUGIN_CONFIG,
                    "setFoliaSupported",
                    "(Z)V",
                    false
            );
            visitLabel(tryEnd);
            visitJumpInsn(Opcodes.GOTO, afterCatch);
            visitLabel(catchHandler);
            visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"});
            visitVarInsn(Opcodes.ASTORE, 2);
            visitLabel(afterCatch);
            visitFrame(Opcodes.F_SAME, 0, null, 0, null);

            visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");
        }
    }
}
