/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.operator.aggregation;

import com.google.common.collect.ImmutableList;
import io.trino.spi.block.Block;
import io.trino.spi.block.ValueBlock;
import io.trino.spi.function.BoundSignature;
import io.trino.spi.type.Type;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.operator.aggregation.AggregationFunctionAdapter.AggregationParameterKind.BLOCK_INDEX;
import static io.trino.operator.aggregation.AggregationFunctionAdapter.AggregationParameterKind.BLOCK_INPUT_CHANNEL;
import static io.trino.operator.aggregation.AggregationFunctionAdapter.AggregationParameterKind.INPUT_CHANNEL;
import static io.trino.operator.aggregation.AggregationFunctionAdapter.AggregationParameterKind.NULLABLE_BLOCK_INPUT_CHANNEL;
import static io.trino.operator.aggregation.AggregationFunctionAdapter.AggregationParameterKind.STATE;
import static java.lang.invoke.MethodHandles.collectArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

public final class AggregationFunctionAdapter
{
    public enum AggregationParameterKind
    {
        INPUT_CHANNEL,
        BLOCK_INPUT_CHANNEL,
        NULLABLE_BLOCK_INPUT_CHANNEL,
        BLOCK_INDEX,
        STATE
    }

    private static final MethodHandle BOOLEAN_TYPE_GETTER;
    private static final MethodHandle LONG_TYPE_GETTER;
    private static final MethodHandle DOUBLE_TYPE_GETTER;
    private static final MethodHandle OBJECT_TYPE_GETTER;

    static {
        try {
            BOOLEAN_TYPE_GETTER = lookup().findVirtual(Type.class, "getBoolean", methodType(boolean.class, Block.class, int.class))
                    .asType(methodType(boolean.class, Type.class, ValueBlock.class, int.class));
            LONG_TYPE_GETTER = lookup().findVirtual(Type.class, "getLong", methodType(long.class, Block.class, int.class))
                    .asType(methodType(long.class, Type.class, ValueBlock.class, int.class));
            DOUBLE_TYPE_GETTER = lookup().findVirtual(Type.class, "getDouble", methodType(double.class, Block.class, int.class))
                    .asType(methodType(double.class, Type.class, ValueBlock.class, int.class));
            OBJECT_TYPE_GETTER = lookup().findVirtual(Type.class, "getObject", methodType(Object.class, Block.class, int.class))
                    .asType(methodType(Object.class, Type.class, ValueBlock.class, int.class));
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private AggregationFunctionAdapter() {}

    public static MethodHandle normalizeInputMethod(
            MethodHandle inputMethod,
            BoundSignature boundSignature,
            AggregationParameterKind... parameterKinds)
    {
        return normalizeInputMethod(inputMethod, boundSignature, ImmutableList.copyOf(parameterKinds));
    }

    public static MethodHandle normalizeInputMethod(
            MethodHandle inputMethod,
            BoundSignature boundSignature,
            List<AggregationParameterKind> parameterKinds)
    {
        return normalizeInputMethod(inputMethod, boundSignature, parameterKinds, 0);
    }

    public static MethodHandle normalizeInputMethod(
            MethodHandle inputMethod,
            BoundSignature boundSignature,
            List<AggregationParameterKind> parameterKinds,
            int lambdaCount)
    {
        requireNonNull(inputMethod, "inputMethod is null");
        requireNonNull(parameterKinds, "parameterKinds is null");
        requireNonNull(boundSignature, "boundSignature is null");

        checkArgument(
                inputMethod.type().parameterCount() - lambdaCount == parameterKinds.size(),
                "Input method has %s parameters, but parameter kinds only has %s items",
                inputMethod.type().parameterCount() - lambdaCount,
                parameterKinds.size());

        List<AggregationParameterKind> stateArgumentKinds = parameterKinds.stream().filter(STATE::equals).collect(toImmutableList());
        List<AggregationParameterKind> inputArgumentKinds = parameterKinds.stream()
                .filter(kind -> kind == INPUT_CHANNEL || kind == BLOCK_INPUT_CHANNEL || kind == NULLABLE_BLOCK_INPUT_CHANNEL)
                .collect(toImmutableList());

        checkArgument(
                boundSignature.getArgumentTypes().size() - lambdaCount == inputArgumentKinds.size(),
                "Bound signature has %s arguments, but parameter kinds only has %s input arguments",
                boundSignature.getArgumentTypes().size() - lambdaCount,
                inputArgumentKinds.size());

        List<AggregationParameterKind> expectedInputArgumentKinds = new ArrayList<>();
        expectedInputArgumentKinds.addAll(stateArgumentKinds);
        for (AggregationParameterKind kind : inputArgumentKinds) {
            expectedInputArgumentKinds.add(kind);
            if (kind == BLOCK_INPUT_CHANNEL || kind == NULLABLE_BLOCK_INPUT_CHANNEL) {
                expectedInputArgumentKinds.add(BLOCK_INDEX);
            }
        }

        checkArgument(
                expectedInputArgumentKinds.equals(parameterKinds),
                "Expected input parameter kinds %s, but got %s",
                expectedInputArgumentKinds,
                parameterKinds);

        for (int argumentIndex = 0; argumentIndex < inputArgumentKinds.size(); argumentIndex++) {
            int parameterIndex = stateArgumentKinds.size() + (argumentIndex * 2);
            AggregationParameterKind inputArgument = inputArgumentKinds.get(argumentIndex);
            if (inputArgument != INPUT_CHANNEL) {
                if (inputArgument == BLOCK_INPUT_CHANNEL || inputArgument == NULLABLE_BLOCK_INPUT_CHANNEL) {
                    checkArgument(ValueBlock.class.isAssignableFrom(inputMethod.type().parameterType(parameterIndex)), "Expected parameter %s to be a ValueBlock", parameterIndex);
                }
                continue;
            }
            Type argumentType = boundSignature.getArgumentType(argumentIndex);

            // process argument through type value getter
            MethodHandle valueGetter;
            if (argumentType.getJavaType().equals(boolean.class)) {
                valueGetter = BOOLEAN_TYPE_GETTER.bindTo(argumentType);
            }
            else if (argumentType.getJavaType().equals(long.class)) {
                valueGetter = LONG_TYPE_GETTER.bindTo(argumentType);
            }
            else if (argumentType.getJavaType().equals(double.class)) {
                valueGetter = DOUBLE_TYPE_GETTER.bindTo(argumentType);
            }
            else {
                valueGetter = OBJECT_TYPE_GETTER.bindTo(argumentType);
                valueGetter = valueGetter.asType(valueGetter.type().changeReturnType(inputMethod.type().parameterType(parameterIndex)));
            }
            inputMethod = collectArguments(inputMethod, parameterIndex, valueGetter);
        }
        return inputMethod;
    }
}
