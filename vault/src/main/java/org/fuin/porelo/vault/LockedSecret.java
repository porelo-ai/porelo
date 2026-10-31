package org.fuin.porelo.vault;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** A secret kept outside the Java heap until it is released. */
public final class LockedSecret implements AutoCloseable {
    interface Protection {
        default long pageSize() {
            return NativeProtection.nativePageSize();
        }

        void lock(MemorySegment memory);

        void excludeFromDumps(MemorySegment memory);

        void unlock(MemorySegment memory);
    }

    private final Arena arena;
    private final MemorySegment memory;
    private final Protection protection;
    private final int length;
    private boolean closed;

    /** Copies a secret into locked native pages, refusing an unprotected allocation. */
    public LockedSecret(byte[] bytes) {
        this(bytes, new NativeProtection());
    }

    LockedSecret(byte[] bytes, Protection protection) {
        this(bytes, protection, Arena.ofShared());
    }

    LockedSecret(byte[] bytes, Protection protection, Arena arena) {
        this.arena = arena;
        if (bytes.length == 0) {
            arena.close();
            throw new IllegalArgumentException("A locked secret cannot be empty");
        }
        this.protection = protection;
        length = bytes.length;
        try {
            final long page = protection.pageSize();
            memory = arena.allocate(Math.ceilDiv((long) length, page) * page, page);
        } catch (RuntimeException | Error ex) {
            arena.close();
            throw ex;
        }
        boolean locked = false;
        try {
            protection.lock(memory);
            locked = true;
            protection.excludeFromDumps(memory);
            MemorySegment.copy(bytes, 0, memory, ValueLayout.JAVA_BYTE, 0, length);
        } catch (RuntimeException | Error ex) {
            memory.fill((byte) 0);
            try {
                if (locked) {
                    protection.unlock(memory);
                }
            } finally {
                arena.close();
            }
            throw ex;
        }
    }

    /** Returns a temporary copy; the caller must clear it after use. */
    public synchronized byte[] copy() {
        if (closed) {
            throw new VaultException("The secret has been released");
        }
        return memory.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
    }

    /** Releases this secret. */
    @Override
    public synchronized void close() {
        if (!closed) {
            memory.fill((byte) 0);
            closed = true;
            try {
                protection.unlock(memory);
            } finally {
                arena.close();
            }
        }
    }

    private static final class NativeProtection implements Protection {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final MethodHandle PAGE_SIZE =
                handle("getpagesize", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        private static final FunctionDescriptor RANGE =
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);
        private static final MethodHandle LOCK = handle("mlock", RANGE);
        private static final MethodHandle UNLOCK = handle("munlock", RANGE);
        private static final MethodHandle ADVISE =
                handle(
                        "madvise",
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT));
        private static final int MADV_DONTDUMP = 16;
        private static final int MADV_DODUMP = 17;

        private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(
                    LINKER.defaultLookup()
                            .find(name)
                            .orElseThrow(
                                    () -> new VaultException("Locked memory requires " + name)),
                    descriptor);
        }

        private static long nativePageSize() {
            try {
                return (int) PAGE_SIZE.invokeExact();
            } catch (Throwable ex) {
                throw new VaultException("Cannot determine the native page size", ex);
            }
        }

        @Override
        public void lock(MemorySegment memory) {
            range(LOCK, memory, "mlock");
        }

        @Override
        public void excludeFromDumps(MemorySegment memory) {
            advise(memory, MADV_DONTDUMP);
        }

        private static void advise(MemorySegment memory, int advice) {
            if (!System.getProperty("os.name", "").equals("Linux")) {
                return;
            }
            try {
                if ((int) ADVISE.invokeExact(memory, memory.byteSize(), advice) != 0) {
                    throw new VaultException(
                            "Cannot change dump protection for the host vault share; nothing was kept");
                }
            } catch (VaultException ex) {
                throw ex;
            } catch (Throwable ex) {
                throw new VaultException("Cannot exclude the host vault share from dumps", ex);
            }
        }

        @Override
        public void unlock(MemorySegment memory) {
            try {
                range(UNLOCK, memory, "munlock");
            } finally {
                // The allocator may reuse these erased pages without unmapping them.
                advise(memory, MADV_DODUMP);
            }
        }

        private static void range(MethodHandle handle, MemorySegment memory, String operation) {
            try {
                if ((int) handle.invokeExact(memory, memory.byteSize()) != 0) {
                    throw new VaultException(
                            operation
                                    + " refused the host vault share; check the locked memory limit");
                }
            } catch (VaultException ex) {
                throw ex;
            } catch (Throwable ex) {
                throw new VaultException("Cannot " + operation + " the host vault share", ex);
            }
        }
    }
}
