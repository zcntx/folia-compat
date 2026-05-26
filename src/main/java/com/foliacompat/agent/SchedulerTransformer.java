package com.foliacompat.agent;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Bytecode transformer that intercepts BukkitScheduler calls and redirects to compat layer.
 *
 * Transform rules:
 * 1. Bukkit.getScheduler() -> CompatSchedulerHolder.getScheduler() (in plugin code)
 * 2. Bukkit.isPrimaryThread() -> MainThreadProxy.isPrimaryThread() (in plugin code)
 *
 * Note: The primary interception is done at runtime via reflection in FoliaCompatPlugin,
 * which replaces CraftServer.scheduler with CompatScheduler. This ensures ALL callers
 * (including jar-in-jar plugins like LuckPerms) get our scheduler.
 * This transformer provides an additional optimization: short-circuiting the
 * Bukkit.getScheduler() delegation chain for classes we CAN transform.
 *
 * Key design:
 * - org/bukkit/ excluded: Bukkit/Server classes are in platform ClassLoader,
 *   our injected com.foliacompat classes are not visible there
 * - BukkitRunnable is whitelisted: its runTask/cancel methods call Bukkit.getScheduler()
 * - Uses COMPUTE_MAXS to avoid COMPUTE_FRAMES ClassLoader issues
 */
public class SchedulerTransformer implements ClassFileTransformer {

    private static final Logger LOGGER = Logger.getLogger("FoliaCompat-Agent");

    private static final byte[] GET_SCHEDULER_PATTERN = "getScheduler".getBytes();
    private static final byte[] IS_PRIMARY_THREAD_PATTERN = "isPrimaryThread".getBytes();

    /**
     * Classes allowed even if they match an excluded prefix.
     *
     * NOTE: BukkitRunnable was previously whitelisted here, but this caused
     * ClassNotFoundException in plugins that can't see com.foliacompat classes
     * (e.g. PowerRanks). Since the reflection injection (replacing
     * CraftServer.scheduler) already ensures BukkitRunnable's internal
     * Bukkit.getScheduler() returns CompatScheduler, bytecode transformation
     * of BukkitRunnable is unnecessary and harmful.
     */
    private static final Set<String> ALLOWED_CLASSES = Set.of();

    // Excluded class prefixes
    private static final String[] EXCLUDED_PREFIXES = {
            "com/foliacompat/",
            "java/",
            "javax/",
            "sun/",
            "org/objectweb/asm/",
            "io/papermc/",
            "org/bukkit/",
            "org/apache/",
            "org/intellij/",
            "org/jetbrains/",
            "joptsimple/",
            "oshi/",
            "com/google/",
            "com/mojang/",
            "it/unimi/",
            "net/kyori/",
            "io/netty/",
            "net/minecraft/",
            "ca/spottedleaf/",
            "com/destroystokyo/",
            "co/aikar/"
    };

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;

        // Whitelisted classes bypass prefix exclusion
        if (!ALLOWED_CLASSES.contains(className)) {
            for (String prefix : EXCLUDED_PREFIXES) {
                if (className.startsWith(prefix)) return null;
            }
        }

        // Fast bytecode scan
        if (!containsTargetMethod(classfileBuffer)) return null;

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

            SchedulerClassVisitor visitor = new SchedulerClassVisitor(writer, className);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (visitor.isModified()) {
                LOGGER.info("[FoliaCompat-Agent] Transformed class: " + className);
                return writer.toByteArray();
            }
        } catch (Exception e) {
            LOGGER.warning("[FoliaCompat-Agent] Failed to transform " + className + ": " + e.getMessage());
        }

        return null;
    }

    private static boolean containsTargetMethod(byte[] classfileBuffer) {
        return indexOf(classfileBuffer, GET_SCHEDULER_PATTERN) >= 0
                || indexOf(classfileBuffer, IS_PRIMARY_THREAD_PATTERN) >= 0;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static class SchedulerClassVisitor extends ClassVisitor {

        private final String className;
        private boolean modified = false;

        SchedulerClassVisitor(ClassVisitor cv, String className) {
            super(Opcodes.ASM9, cv);
            this.className = className;
        }

        boolean isModified() {
            return modified;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new SchedulerMethodVisitor(mv, access, name, descriptor);
        }

        private class SchedulerMethodVisitor extends AdviceAdapter {

            SchedulerMethodVisitor(MethodVisitor mv, int access, String name, String descriptor) {
                super(Opcodes.ASM9, mv, access, name, descriptor);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                // Intercept Bukkit.getScheduler() static calls
                if (opcode == Opcodes.INVOKESTATIC
                        && "org/bukkit/Bukkit".equals(owner)
                        && "getScheduler".equals(name)
                        && "()Lorg/bukkit/scheduler/BukkitScheduler;".equals(descriptor)) {

                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "com/foliacompat/scheduler/CompatSchedulerHolder",
                            "getScheduler",
                            "()Lorg/bukkit/scheduler/BukkitScheduler;",
                            false
                    );
                    modified = true;
                    return;
                }

                // Intercept Bukkit.isPrimaryThread() static calls
                if (opcode == Opcodes.INVOKESTATIC
                        && "org/bukkit/Bukkit".equals(owner)
                        && "isPrimaryThread".equals(name)
                        && "()Z".equals(descriptor)) {

                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "com/foliacompat/thread/MainThreadProxy",
                            "isPrimaryThread",
                            "()Z",
                            false
                    );
                    modified = true;
                    return;
                }

                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }
    }
}
