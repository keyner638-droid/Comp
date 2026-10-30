package com.comp;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(CompMod.MODID)
public class CompMod {
    public static final String MODID = "comp";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CompMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Comp cargado");
    }
}
