package com.comp;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

@Mod(CompMod.MODID)
public class CompMod {
    public static final String MODID = "comp";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final int MAX_LEVELS = 30;

    public CompMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(CompMod::registerPayloads);
        NeoForge.EVENT_BUS.addListener(CompMod::onRightClick);
        LOGGER.info("Comp cargado");
    }

    // ---------- Red ----------

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(EnchantRequest.TYPE, EnchantRequest.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        apply(sp, payload.levels());
                    }
                }));
    }

    public record EnchantRequest(Map<ResourceLocation, Integer> levels) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<EnchantRequest> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "enchant_request"));
        public static final StreamCodec<FriendlyByteBuf, EnchantRequest> CODEC =
                StreamCodec.of(EnchantRequest::write, EnchantRequest::read);

        private static void write(FriendlyByteBuf buf, EnchantRequest r) {
            buf.writeVarInt(r.levels().size());
            r.levels().forEach((id, lvl) -> {
                buf.writeResourceLocation(id);
                buf.writeVarInt(lvl);
            });
        }

        private static EnchantRequest read(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            if (n > 64) {
                throw new IllegalArgumentException("Demasiados encantamientos");
            }
            Map<ResourceLocation, Integer> m = new HashMap<>();
            for (int i = 0; i < n; i++) {
                m.put(buf.readResourceLocation(), buf.readVarInt());
            }
            return new EnchantRequest(m);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------- Lógica compartida ----------

    public static List<Holder<Enchantment>> available(RegistryAccess access, ItemStack stack) {
        List<Holder<Enchantment>> list = new ArrayList<>();
        if (stack.isEmpty()) {
            return list;
        }
        access.registryOrThrow(Registries.ENCHANTMENT).holders().forEach(h -> {
            if (h.is(EnchantmentTags.CURSE)) {
                return;
            }
            if (h.value().isSupportedItem(stack)) {
                list.add(h);
            }
        });
        list.sort(Comparator.comparing((Holder<Enchantment> h) -> h.value().description().getString()));
        return list;
    }

    public static int cost(Holder<Enchantment> h, int level) {
        int c = 1 + level * 2;
        if (h.is(EnchantmentTags.TREASURE)) {
            c += 8;
        }
        return c;
    }

    public static int lapisFor(int totalCost) {
        return Math.max(1, Math.min(3, (totalCost + 9) / 10));
    }

    public static int totalCost(Map<Holder<Enchantment>, Integer> chosen, ItemEnchantments existing) {
        int total = 0;
        for (Map.Entry<Holder<Enchantment>, Integer> e : chosen.entrySet()) {
            if (e.getValue() > existing.getLevel(e.getKey())) {
                total += cost(e.getKey(), e.getValue());
            }
        }
        return total;
    }

    public static boolean compatibles(Holder<Enchantment> a, Holder<Enchantment> b) {
        return a.equals(b) || Enchantment.areCompatible(a, b);
    }

    public static int countLapis(Player p) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.LAPIS_LAZULI)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static boolean nearTable(Player p) {
        BlockPos c = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-6, -4, -6), c.offset(6, 4, 6))) {
            if (p.level().getBlockState(pos).is(Blocks.ENCHANTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    // ---------- Servidor ----------

    private static void apply(ServerPlayer player, Map<ResourceLocation, Integer> req) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty() || !nearTable(player)) {
            return;
        }
        Registry<Enchantment> reg = player.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        ItemEnchantments existing = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);

        Map<Holder<Enchantment>, Integer> chosen = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Integer> e : req.entrySet()) {
            Optional<Holder.Reference<Enchantment>> opt =
                    reg.getHolder(ResourceKey.create(Registries.ENCHANTMENT, e.getKey()));
            if (opt.isEmpty()) {
                return;
            }
            Holder<Enchantment> h = opt.get();
            int lvl = e.getValue();
            if (lvl <= 0 || lvl > h.value().getMaxLevel()) {
                return;
            }
            if (h.is(EnchantmentTags.CURSE) || !h.value().isSupportedItem(stack)) {
                return;
            }
            if (lvl <= existing.getLevel(h)) {
                continue;
            }
            chosen.put(h, lvl);
        }
        if (chosen.isEmpty()) {
            return;
        }

        List<Holder<Enchantment>> all = new ArrayList<>(existing.keySet());
        for (Holder<Enchantment> h : chosen.keySet()) {
            if (!all.contains(h)) {
                all.add(h);
            }
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (!compatibles(all.get(i), all.get(j))) {
                    player.displayClientMessage(Component.literal("Encantamientos incompatibles"), true);
                    return;
                }
            }
        }

        int total = totalCost(chosen, existing);
        int lapis = lapisFor(total);
        boolean free = player.getAbilities().instabuild;
        if (total > MAX_LEVELS) {
            player.displayClientMessage(Component.literal("Máximo " + MAX_LEVELS + " niveles"), true);
            return;
        }
        if (!free) {
            if (player.experienceLevel < total) {
                player.displayClientMessage(Component.literal("Te faltan niveles de XP"), true);
                return;
            }
            if (countLapis(player) < lapis) {
                player.displayClientMessage(Component.literal("Te falta lapislázuli"), true);
                return;
            }
            player.giveExperienceLevels(-total);
            int left = lapis;
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(Items.LAPIS_LAZULI)) {
                    int take = Math.min(left, s.getCount());
                    s.shrink(take);
                    left -= take;
                }
            }
        }
        for (Map.Entry<Holder<Enchantment>, Integer> e : chosen.entrySet()) {
            stack.enchant(e.getKey(), e.getValue());
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE,
                SoundSource.BLOCKS, 1.0F, 1.0F);
        player.displayClientMessage(Component.literal("¡Encantado!"), true);
    }

    // ---------- Click derecho en la mesa ----------

    private static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (e.getHand() != InteractionHand.MAIN_HAND || p.isShiftKeyDown()) {
            return;
        }
        if (!e.getLevel().getBlockState(e.getPos()).is(Blocks.ENCHANTING_TABLE)) {
            return;
        }
        ItemStack stack = p.getMainHandItem();
        if (available(e.getLevel().registryAccess(), stack).isEmpty()) {
            return;
        }
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getLevel().isClientSide()) {
            ClientHooks.open();
        }
    }

    // ---------- Cliente ----------

    static class ClientHooks {
        static void open() {
            Minecraft mc = Minecraft.getInstance();
            Player p = mc.player;
            if (p == null) {
                return;
            }
            ItemStack stack = p.getMainHandItem();
            List<Holder<Enchantment>> list = available(p.level().registryAccess(), stack);
            mc.setScreen(new EnchantScreen(stack.copy(), list));
        }
    }

    public static class EnchantScreen extends Screen {
        private record Row(Holder<Enchantment> h, int x, int y, int w, Button minus, Button plus) {}

        private final ItemStack stack;
        private final ItemEnchantments existing;
        private final List<Holder<Enchantment>> list;
        private final Map<Holder<Enchantment>, Integer> chosen = new LinkedHashMap<>();
        private final List<Row> rows = new ArrayList<>();
        private Button enchantButton;

        EnchantScreen(ItemStack stack, List<Holder<Enchantment>> list) {
            super(Component.literal("Mesa de encantamientos"));
            this.stack = stack;
            this.list = list;
            this.existing = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            for (Holder<Enchantment> h : list) {
                chosen.put(h, existing.getLevel(h));
            }
        }

        @Override
        protected void init() {
            rows.clear();
            int avail = this.height - 90;
            int perCol = Math.max(3, avail / 22);
            int cols = Math.max(1, (list.size() + perCol - 1) / perCol);
            int colW = Math.min(150, (this.width - 10) / cols);
            int startX = (this.width - cols * colW) / 2;
            int startY = 40;
            for (int i = 0; i < list.size(); i++) {
                int c = i / perCol;
                int r = i % perCol;
                int x = startX + c * colW;
                int y = startY + r * 22;
                Holder<Enchantment> h = list.get(i);
                Button minus = Button.builder(Component.literal("-"), b -> change(h, -1))
                        .bounds(x, y, 18, 20).build();
                Button plus = Button.builder(Component.literal("+"), b -> change(h, 1))
                        .bounds(x + colW - 22, y, 18, 20).build();
                this.addRenderableWidget(minus);
                this.addRenderableWidget(plus);
                rows.add(new Row(h, x, y, colW, minus, plus));
            }
            enchantButton = Button.builder(Component.literal("Encantar"), b -> send())
                    .bounds(this.width / 2 - 100, this.height - 28, 96, 20).build();
            this.addRenderableWidget(enchantButton);
            this.addRenderableWidget(Button.builder(Component.literal("Cerrar"), b -> this.onClose())
                    .bounds(this.width / 2 + 4, this.height - 28, 96, 20).build());
            refresh();
        }

        private boolean blocked(Holder<Enchantment> h) {
            for (Map.Entry<Holder<Enchantment>, Integer> e : chosen.entrySet()) {
                if (e.getValue() > 0 && !compatibles(h, e.getKey())) {
                    return true;
                }
            }
            return false;
        }

        private void change(Holder<Enchantment> h, int delta) {
            int cur = chosen.getOrDefault(h, 0);
            int min = existing.getLevel(h);
            int max = h.value().getMaxLevel();
            int next = Mth.clamp(cur + delta, min, max);
            if (next > 0 && cur == 0 && blocked(h)) {
                return;
            }
            chosen.put(h, next);
            refresh();
        }

        private boolean canPay(int cost) {
            Player p = this.minecraft.player;
            if (p == null) {
                return false;
            }
            if (p.getAbilities().instabuild) {
                return true;
            }
            return p.experienceLevel >= cost && countLapis(p) >= lapisFor(cost);
        }

        private void refresh() {
            for (Row r : rows) {
                int lvl = chosen.getOrDefault(r.h(), 0);
                int min = existing.getLevel(r.h());
                int max = r.h().value().getMaxLevel();
                r.minus().active = lvl > min;
                r.plus().active = lvl < max && (lvl > 0 || !blocked(r.h()));
            }
            int cost = totalCost(chosen, existing);
            enchantButton.active = cost > 0 && cost <= MAX_LEVELS && canPay(cost);
        }

        private void send() {
            Map<ResourceLocation, Integer> out = new HashMap<>();
            for (Map.Entry<Holder<Enchantment>, Integer> e : chosen.entrySet()) {
                if (e.getValue() > existing.getLevel(e.getKey())) {
                    e.getKey().unwrapKey().ifPresent(k -> out.put(k.location(), e.getValue()));
                }
            }
            if (!out.isEmpty()) {
                PacketDistributor.sendToServer(new EnchantRequest(out));
            }
            this.onClose();
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            g.drawCenteredString(this.font, this.title, this.width / 2, 6, 0xFFFFFF);
            g.renderItem(stack, this.width / 2 - 8, 18);
            for (Row r : rows) {
                int lvl = chosen.getOrDefault(r.h(), 0);
                int min = existing.getLevel(r.h());
                boolean isBlocked = lvl == 0 && blocked(r.h());
                int color = isBlocked ? 0x777777 : (lvl > min ? 0x55FF55 : (lvl > 0 ? 0xFFFF55 : 0xFFFFFF));
                String text = r.h().value().description().getString() + (lvl > 0 ? " " + lvl : "");
                g.drawString(this.font, this.font.plainSubstrByWidth(text, r.w() - 48),
                        r.x() + 22, r.y() + 6, color);
            }
            int cost = totalCost(chosen, existing);
            Player p = this.minecraft.player;
            int lapisHave = p == null ? 0 : countLapis(p);
            String info = "Costo: " + cost + "/" + MAX_LEVELS + " niveles | Lapis: "
                    + lapisFor(Math.max(cost, 1)) + " (tienes " + lapisHave + ")";
            boolean bad = cost > MAX_LEVELS || (cost > 0 && !canPay(cost));
            g.drawCenteredString(this.font, info, this.width / 2, this.height - 42, bad ? 0xFF5555 : 0xFFFFFF);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
