package net.coderbot.iris.gl.uniform;

import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import org.joml.Vector2i;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public interface DynamicUniformHolder extends UniformHolder {
	DynamicUniformHolder uniform1f(String name, FloatSupplier value, ValueUpdateNotifier notifier);
	DynamicUniformHolder uniform1f(String name, IntSupplier value, ValueUpdateNotifier notifier);
	DynamicUniformHolder uniform1f(String name, DoubleSupplier value, ValueUpdateNotifier notifier);
	DynamicUniformHolder uniform1i(String name, IntSupplier value, ValueUpdateNotifier notifier);
	DynamicUniformHolder uniform2i(String name, Supplier<Vector2i> value, ValueUpdateNotifier notifier);
    DynamicUniformHolder uniform3i(String name, Supplier<Vector3i> value, ValueUpdateNotifier notifier);
	DynamicUniformHolder uniform4f(String name, Supplier<Vector4f> value, ValueUpdateNotifier notifier);
    DynamicUniformHolder uniform4fArray(String name, Supplier<float[]> value, ValueUpdateNotifier notifier);
	DynamicUniformHolder uniform4i(String name, Supplier<Vector4i> value, ValueUpdateNotifier notifier);
}
