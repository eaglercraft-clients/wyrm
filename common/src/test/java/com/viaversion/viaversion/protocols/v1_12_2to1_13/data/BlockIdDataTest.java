/*
 * This file is part of ViaVersion - https://github.com/ViaVersion/ViaVersion
 * Copyright (C) 2016-2026 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.viaversion.viaversion.protocols.v1_12_2to1_13.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStreamReader;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** BlockIdData reads its JSON without a TypeToken (TeaVM keeps no generic signatures); the result must be what Gson's typed parse gives. */
class BlockIdDataTest {

    @Test
    void readsTheSameMappingsAsGsonDoes() throws Exception {
        BlockIdData.init();
        assertFalse(BlockIdData.blockIdMapping.isEmpty());
        assertFalse(BlockIdData.numberIdToString.isEmpty());

        final Gson gson = new Gson();
        final Map<String, String[]> ids;
        try (InputStreamReader reader = new InputStreamReader(
            getClass().getClassLoader().getResourceAsStream("assets/viaversion/data/blockIds1.12to1.13.json"))) {
            ids = gson.fromJson(reader, new TypeToken<Map<String, String[]>>() {
            }.getType());
        }
        assertEquals(ids.size(), BlockIdData.blockIdMapping.size());
        for (final Map.Entry<String, String[]> entry : ids.entrySet()) {
            assertArrayEquals(entry.getValue(), BlockIdData.blockIdMapping.get(entry.getKey()), entry.getKey());
        }

        final Map<Integer, String> numbers;
        try (InputStreamReader reader = new InputStreamReader(
            getClass().getClassLoader().getResourceAsStream("assets/viaversion/data/blockNumberToString1.12.json"))) {
            numbers = gson.fromJson(reader, new TypeToken<Map<Integer, String>>() {
            }.getType());
        }
        assertEquals(numbers.size(), BlockIdData.numberIdToString.size());
        for (final Map.Entry<Integer, String> entry : numbers.entrySet()) {
            assertEquals(entry.getValue(), BlockIdData.numberIdToString.get((int) entry.getKey()));
        }
    }
}
