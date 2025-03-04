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
package io.trino.plugin.kafka.encoder.json;

import io.trino.plugin.kafka.encoder.json.format.JsonDateTimeFormatter;
import io.trino.spi.type.SqlTimestampWithTimeZone;
import io.trino.spi.type.TimeZoneKey;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;

import static io.trino.plugin.kafka.encoder.json.format.DateTimeFormat.MILLISECONDS_SINCE_EPOCH;
import static io.trino.plugin.kafka.encoder.json.format.util.TimeConversions.scaleNanosToMillis;
import static io.trino.spi.type.TimeZoneKey.UTC_KEY;
import static io.trino.testing.DateTimeTestingUtils.sqlTimeOf;
import static io.trino.testing.DateTimeTestingUtils.sqlTimestampOf;
import static java.time.temporal.ChronoField.EPOCH_DAY;
import static java.time.temporal.ChronoField.MILLI_OF_DAY;
import static java.time.temporal.ChronoField.NANO_OF_DAY;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;

public class TestMillisecondsJsonDateTimeFormatter
{
    private static JsonDateTimeFormatter getFormatter()
    {
        return MILLISECONDS_SINCE_EPOCH.getFormatter(Optional.empty());
    }

    @Test
    public void testTime()
    {
        testTime(LocalTime.of(15, 36, 25, 123000000));
        testTime(LocalTime.of(15, 36, 25, 0));
    }

    private void testTime(LocalTime time)
    {
        String formatted = getFormatter().formatTime(sqlTimeOf(3, time), 3);
        assertThat(Long.parseLong(formatted)).isEqualTo(time.getLong(MILLI_OF_DAY));
    }

    @Test
    public void testTimestamp()
    {
        testTimestamp(LocalDateTime.of(2020, 8, 18, 12, 38, 29, 123000000));
        testTimestamp(LocalDateTime.of(1970, 1, 1, 0, 0, 0, 0));
        testTimestamp(LocalDateTime.of(1800, 8, 18, 12, 38, 29, 123000000));
    }

    private void testTimestamp(LocalDateTime dateTime)
    {
        String formattedStr = getFormatter().formatTimestamp(sqlTimestampOf(3, dateTime));
        assertThat(Long.parseLong(formattedStr)).isEqualTo(DAYS.toMillis(dateTime.getLong(EPOCH_DAY)) + scaleNanosToMillis(dateTime.getLong(NANO_OF_DAY)));
    }

    @Test
    public void testTimestampWithTimeZone()
    {
        testTimestampWithTimeZone(ZonedDateTime.of(2020, 8, 18, 12, 38, 29, 123000000, UTC_KEY.getZoneId()));
        testTimestampWithTimeZone(ZonedDateTime.of(2020, 8, 18, 12, 38, 29, 123000000, TimeZoneKey.getTimeZoneKey("America/New_York").getZoneId()));
        testTimestampWithTimeZone(ZonedDateTime.of(1800, 8, 18, 12, 38, 29, 123000000, UTC_KEY.getZoneId()));
        testTimestampWithTimeZone(ZonedDateTime.of(2020, 8, 18, 12, 38, 29, 123000000, TimeZoneKey.getTimeZoneKey("Asia/Hong_Kong").getZoneId()));
        testTimestampWithTimeZone(ZonedDateTime.of(2020, 8, 18, 12, 38, 29, 123000000, TimeZoneKey.getTimeZoneKey("Africa/Mogadishu").getZoneId()));
        testTimestampWithTimeZone(ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, UTC_KEY.getZoneId()));
    }

    private void testTimestampWithTimeZone(ZonedDateTime zonedDateTime)
    {
        String formattedStr = getFormatter().formatTimestampWithZone(SqlTimestampWithTimeZone.fromInstant(3, zonedDateTime.toInstant(), zonedDateTime.getZone()));
        assertThat(Long.parseLong(formattedStr)).isEqualTo(zonedDateTime.toInstant().toEpochMilli());
    }
}
