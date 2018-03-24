/*
 * Copyright (c) 2017-2018 Aion foundation.
 *
 * This file is part of the aion network project.
 *
 * The aion network project is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * The aion network project is distributed in the hope that it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the aion network project source files.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * Contributors to the aion source files in decreasing order of code volume:
 *
 * Aion foundation.
 *
 */

package org.aion.zero.impl.config;

import org.junit.Assert;
import org.junit.Test;

/**
 * @author chris
 *
 * make test last step to finish to keep
 * test config.xml with same id as [NODE-ID-PLACEHOLDER]
 */
public class CfgAionTest {

    @Test
    public void test() {

        // load default config xml
        CfgAion cfg = CfgAion.inst();

        // default id on runtime should be random created uuid
        String placeholder = "[NODE-ID-PLACEHOLDER]";
        Assert.assertNotEquals(placeholder, cfg.getId());

        // default instance has no nodes
        Assert.assertEquals(0, cfg.getNet().getNodes().length);

        // default id placeholder -> should return true;
        boolean shouldWriteBack = cfg.fromXML();
        Assert.assertTrue(shouldWriteBack);

        // ship 5 seed nodes on current version xml after load from file
        // test case needs to be updated when we update default config.xml shipped
        Assert.assertEquals(5, cfg.getNet().getNodes().length);

        // save back to xml with existing new generated id
        String currentId = cfg.getId();
        cfg.toXML(new String[] { "--id=" + currentId });

        // check id persists after save then read
        cfg.fromXML();
        Assert.assertEquals(currentId, cfg.getId());

        // restore default test config.xml
        cfg.setId(placeholder);
        cfg.toXML(null);


    }
}
