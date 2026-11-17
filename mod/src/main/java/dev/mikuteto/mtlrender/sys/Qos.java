package dev.mikuteto.mtlrender.sys;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class Qos {
	public static final int USER_INTERACTIVE = 0x21;
	public static final int USER_INITIATED = 0x19;
	public static final int UTILITY = 0x11;

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Linker LINKER = linker();
	private static final MethodHandle SET_SELF = handle(
		"pthread_set_qos_class_self_np",
		FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
	);
	private static final MethodHandle SELF = handle(
		"qos_class_self",
		FunctionDescriptor.of(ValueLayout.JAVA_INT)
	);
	private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean RENDER_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean WORKER_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean UTILITY_LOGGED = new AtomicBoolean();
	private static final ThreadLocal<Boolean> WORKER_SET = ThreadLocal.withInitial(() -> false);
	private static final ThreadLocal<Boolean> UTILITY_SET = ThreadLocal.withInitial(() -> false);

	private Qos() {
	}

	public static void setRenderThread() {
		apply(USER_INTERACTIVE, "render thread", RENDER_LOGGED);
	}

	public static void setWorkerThread() {
		if (!WORKER_SET.get()) {
			WORKER_SET.set(true);
			apply(USER_INITIATED, "chunk worker", WORKER_LOGGED);
		}
	}

	public static void setUtilityQoS() {
		if (!UTILITY_SET.get()) {
			UTILITY_SET.set(true);
			apply(UTILITY, "utility", UTILITY_LOGGED);
		}
	}

	public static void setUtilityThread() {
		setUtilityQoS();
	}

	private static void apply(int qos, String name, AtomicBoolean actualLogged) {
		if (SET_SELF == null) {
			failure("pthread_set_qos_class_self_np is unavailable", null);
		} else {
			try {
				int result = (int) SET_SELF.invokeExact(qos, 0);
				if (result != 0) {
					failure("pthread_set_qos_class_self_np returned " + result, null);
				}
			} catch (Throwable e) {
				failure("pthread_set_qos_class_self_np failed", e);
			}
		}
		if (SELF == null) {
			failure("qos_class_self is unavailable", null);
			return;
		}
		try {
			int actual = (int) SELF.invokeExact();
			if (actualLogged.compareAndSet(false, true)) {
				LOGGER.info("MTLRender {} QoS: 0x{}", name, Integer.toHexString(actual));
			}
		} catch (Throwable e) {
			failure("qos_class_self failed", e);
		}
	}

	private static Linker linker() {
		try {
			return Linker.nativeLinker();
		} catch (Throwable e) {
			return null;
		}
	}

	private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
		if (LINKER == null) {
			return null;
		}
		try {
			MemorySegment address = LINKER.defaultLookup().find(name).orElse(MemorySegment.NULL);
			return address.equals(MemorySegment.NULL) ? null : LINKER.downcallHandle(address, descriptor);
		} catch (Throwable e) {
			return null;
		}
	}

	private static void failure(String message, Throwable cause) {
		if (FAILURE_LOGGED.compareAndSet(false, true)) {
			if (cause == null) {
				LOGGER.warn("MTLRender QoS ignored: {}", message);
			} else {
				LOGGER.warn("MTLRender QoS ignored: {}", message, cause);
			}
		}
	}
}
