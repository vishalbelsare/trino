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
package io.trino.parquet.writer;

import org.apache.parquet.format.ConvertedType;
import org.apache.parquet.format.FieldRepetitionType;
import org.apache.parquet.format.SchemaElement;
import org.apache.parquet.format.Type;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.TypeVisitor;

import java.util.ArrayList;
import java.util.List;

import static io.trino.parquet.ParquetMetadataConverter.convertToLogicalType;

class MessageTypeConverter
{
    private MessageTypeConverter() {}

    static List<SchemaElement> toParquetSchema(MessageType schema)
    {
        List<SchemaElement> result = new ArrayList<>();
        addToList(result, schema);
        return result;
    }

    private static void addToList(List<SchemaElement> result, org.apache.parquet.schema.Type field)
    {
        field.accept(new TypeVisitor()
        {
            @Override
            public void visit(PrimitiveType primitiveType)
            {
                SchemaElement element = new SchemaElement(primitiveType.getName());
                element.setRepetition_type(toParquetRepetition(primitiveType.getRepetition()));
                element.setType(getType(primitiveType.getPrimitiveTypeName()));
                if (primitiveType.getOriginalType() != null) {
                    element.setConverted_type(getConvertedType(primitiveType.getOriginalType()));
                }
                if (primitiveType.getLogicalTypeAnnotation() != null) {
                    element.setLogicalType(convertToLogicalType(primitiveType.getLogicalTypeAnnotation()));
                }
                if (primitiveType.getDecimalMetadata() != null) {
                    element.setPrecision(primitiveType.getDecimalMetadata().getPrecision());
                    element.setScale(primitiveType.getDecimalMetadata().getScale());
                }
                if (primitiveType.getTypeLength() > 0) {
                    element.setType_length(primitiveType.getTypeLength());
                }
                if (primitiveType.getId() != null) {
                    element.setField_id(primitiveType.getId().intValue());
                }
                result.add(element);
            }

            @Override
            public void visit(MessageType messageType)
            {
                SchemaElement element = new SchemaElement(messageType.getName());
                if (messageType.getId() != null) {
                    element.setField_id(messageType.getId().intValue());
                }
                visitChildren(result, messageType.asGroupType(), element);
            }

            @Override
            public void visit(GroupType groupType)
            {
                SchemaElement element = new SchemaElement(groupType.getName());
                element.setRepetition_type(toParquetRepetition(groupType.getRepetition()));
                if (groupType.getOriginalType() != null) {
                    element.setConverted_type(getConvertedType(groupType.getOriginalType()));
                }
                if (groupType.getLogicalTypeAnnotation() != null) {
                    element.setLogicalType(convertToLogicalType(groupType.getLogicalTypeAnnotation()));
                }
                if (groupType.getId() != null) {
                    element.setField_id(groupType.getId().intValue());
                }
                visitChildren(result, groupType, element);
            }

            private void visitChildren(List<SchemaElement> result,
                    GroupType groupType, SchemaElement element)
            {
                element.setNum_children(groupType.getFieldCount());
                result.add(element);
                for (org.apache.parquet.schema.Type field : groupType.getFields()) {
                    addToList(result, field);
                }
            }
        });
    }

    private static FieldRepetitionType toParquetRepetition(org.apache.parquet.schema.Type.Repetition repetition)
    {
        return FieldRepetitionType.valueOf(repetition.name());
    }

    private static org.apache.parquet.format.Type getType(PrimitiveType.PrimitiveTypeName type)
    {
        switch (type) {
            case INT64 -> {
                return Type.INT64;
            }
            case INT32 -> {
                return Type.INT32;
            }
            case BOOLEAN -> {
                return Type.BOOLEAN;
            }
            case BINARY -> {
                return Type.BYTE_ARRAY;
            }
            case FLOAT -> {
                return Type.FLOAT;
            }
            case DOUBLE -> {
                return Type.DOUBLE;
            }
            case INT96 -> {
                return Type.INT96;
            }
            case FIXED_LEN_BYTE_ARRAY -> {
                return Type.FIXED_LEN_BYTE_ARRAY;
            }
        }
        throw new RuntimeException("Unknown primitive type " + type);
    }

    private static ConvertedType getConvertedType(OriginalType type)
    {
        return switch (type) {
            case UTF8 -> ConvertedType.UTF8;
            case MAP -> ConvertedType.MAP;
            case MAP_KEY_VALUE -> ConvertedType.MAP_KEY_VALUE;
            case LIST -> ConvertedType.LIST;
            case ENUM -> ConvertedType.ENUM;
            case DECIMAL -> ConvertedType.DECIMAL;
            case DATE -> ConvertedType.DATE;
            case TIME_MICROS -> ConvertedType.TIME_MICROS;
            case TIME_MILLIS -> ConvertedType.TIME_MILLIS;
            case TIMESTAMP_MILLIS -> ConvertedType.TIMESTAMP_MILLIS;
            case TIMESTAMP_MICROS -> ConvertedType.TIMESTAMP_MICROS;
            case INTERVAL -> ConvertedType.INTERVAL;
            case INT_8 -> ConvertedType.INT_8;
            case INT_16 -> ConvertedType.INT_16;
            case INT_32 -> ConvertedType.INT_32;
            case INT_64 -> ConvertedType.INT_64;
            case UINT_8 -> ConvertedType.UINT_8;
            case UINT_16 -> ConvertedType.UINT_16;
            case UINT_32 -> ConvertedType.UINT_32;
            case UINT_64 -> ConvertedType.UINT_64;
            case JSON -> ConvertedType.JSON;
            case BSON -> ConvertedType.BSON;
        };
    }
}
