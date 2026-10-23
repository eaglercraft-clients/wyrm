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

package com.viaversion.viaversion.protocols.v1_12_2to1_13.eagler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class EaglerChannelsTest {

    @Test
    void mapsEaglerChannelsToValidIdentifiers() {
        assertEquals("eag:skins-1.8", EaglerChannels.toModern("EAG|Skins-1.8"));
        assertEquals("eag:myskin", EaglerChannels.toModern("EAG|MySkin"));
        assertEquals("eag:update_cert-1.8", EaglerChannels.toModern("EAG|Update Cert-1.8"));
    }

    @Test
    void producedIdentifiersOnlyUseLegalCharacters() {
        final String path = EaglerChannels.toModern("EAG|Weiße|Kat/ze?").substring("eag:".length());
        assertTrue(path.matches("[a-z0-9_.\\-/]+"), path);
    }

    @Test
    void emptyNameStillGivesAnIdentifier() {
        assertEquals("eag:_", EaglerChannels.toModern("EAG|"));
    }

    @Test
    void recognisesEaglerChannels() {
        assertTrue(EaglerChannels.isLegacyEaglerChannel("EAG|Capes-1.8", Collections.emptyList()));
        assertFalse(EaglerChannels.isLegacyEaglerChannel("MC|Brand", Collections.emptyList()));
        assertFalse(EaglerChannels.isLegacyEaglerChannel(null, Collections.emptyList()));
        assertTrue(EaglerChannels.isLegacyEaglerChannel("Custom", Arrays.asList("Custom")));
        assertTrue(EaglerChannels.isModernEaglerChannel("eag:skins-1.8"));
        assertFalse(EaglerChannels.isModernEaglerChannel("minecraft:brand"));
    }

    @Test
    void storageRestoresTheClientsSpelling() {
        final EaglerChannelStorage storage = new EaglerChannelStorage();
        assertNull(storage.legacyOf("eag:skins-1.8"));
        storage.learn("EAG|Skins-1.8", "eag:skins-1.8");
        assertEquals("EAG|Skins-1.8", storage.legacyOf("eag:skins-1.8"));
        storage.learn("EAG|SKINS-1.8", "eag:skins-1.8"); // the first spelling the server used wins
        assertEquals("EAG|Skins-1.8", storage.legacyOf("eag:skins-1.8"));
    }
}
