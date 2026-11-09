package works.repka.ryscope.neoforge;

import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import works.repka.ryscope.RYScope;
import works.repka.ryscope.neoforge.net.StagePayload;
import works.repka.ryscope.stage.StageDef;

/**
 * Стадии на стороне сервера. Сервер решает всё сам и одинаково для любых клиентов, в том числе без мода:
 * <ul>
 *   <li>читает описания из датапаков ({@code data/<ns>/ryscope_stages/*.json}) и из {@code config/ryscope/stages};</li>
 *   <li>считает стадию открытой по достижению или по выдаче командой {@code /ryscope stage grant};</li>
 *   <li>не отправляет игроку рецепты закрытых стадий с {@code "hidden": true};</li>
 *   <li>не даёт выполнить в сетке крафта рецепт закрытой стадии (если не указано {@code "enforce": false});</li>
 *   <li>сообщает клиенту с модом описания и состояние, чтобы тот показал замки.</li>
 * </ul>
 */
public final class ServerStages {
    public static final String FOLDER = "ryscope_stages";

    private static volatile List<StageDef> defs = List.of();
    /** Какие рецепты относятся к стадии. Считается один раз после загрузки данных. */
    private static Map<StageDef, Set<ResourceLocation>> recipeCache = new HashMap<>();
    private static Object recipeCacheFor;
    private static final Map<UUID, Long> lastNotice = new HashMap<>();

    private ServerStages() {}

    static void register(IEventBus gameBus) {
        gameBus.addListener((AddReloadListenerEvent e) -> e.addListener((ResourceManagerReloadListener) ServerStages::load));
        gameBus.addListener((OnDatapackSyncEvent e) -> e.getRelevantPlayers().forEach(ServerStages::sync));
        gameBus.addListener((AdvancementEvent.AdvancementEarnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) onAdvancement(player, e.getAdvancement());
        });
        // достижение можно и отозвать (командой или модом): тогда стадия закрывается обратно
        gameBus.addListener((AdvancementEvent.AdvancementProgressEvent e) -> {
            if (e.getProgressType() == AdvancementEvent.AdvancementProgressEvent.ProgressType.REVOKE
                && e.getEntity() instanceof ServerPlayer player) {
                onAdvancement(player, e.getAdvancement());
            }
        });
        gameBus.addListener(ServerStages::registerCommands);
        // кто открыл печь, тот и «хозяин» плавки: по его стадиям решается, что печь может плавить
        gameBus.addListener((net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open e) -> {
            if (defs.isEmpty() || !(e.getEntity() instanceof ServerPlayer player)) return;
            var menu = e.getContainer();
            boolean selfWorking = menu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu
                || menu instanceof net.minecraft.world.inventory.CrafterMenu
                || menu instanceof net.minecraft.world.inventory.BrewingStandMenu;
            if (selfWorking && !menu.slots.isEmpty()
                && menu.slots.get(0).container instanceof net.minecraft.world.level.block.entity.BlockEntity block) {
                block.getPersistentData().putUUID(OWNER_KEY, player.getUUID());
                block.setChanged();
            }
        });
        gameBus.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent e) -> verifyHooks());
    }

    // --- описания ---

    private static void load(ResourceManager manager) {
        List<StageDef> loaded = new ArrayList<>();
        for (var e : manager.listResources(FOLDER, p -> p.getPath().endsWith(".json")).entrySet()) {
            String path = e.getKey().getPath();
            String id = e.getKey().getNamespace() + ":" + path.substring(FOLDER.length() + 1, path.length() - 5);
            try (Reader reader = e.getValue().openAsReader()) {
                loaded.add(new StageDef(id, JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception ex) {
                RYScope.LOG.warn("Стадия {} не прочитана: {}", e.getKey(), ex.toString());
            }
        }
        Path dir = FMLPaths.CONFIGDIR.get().resolve(RYScope.MOD_ID).resolve("stages");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".json")).sorted().forEach(f -> {
                    String name = f.getFileName().toString();
                    try (Reader reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                        loaded.add(new StageDef("config:" + name.substring(0, name.length() - 5), JsonParser.parseReader(reader).getAsJsonObject()));
                    } catch (Exception ex) {
                        RYScope.LOG.warn("Стадия {} не прочитана: {}", f, ex.toString());
                    }
                });
            } catch (Exception ex) {
                RYScope.LOG.warn("Папка стадий не прочитана: {}", ex.toString());
            }
        }
        loaded.sort((a, b) -> Integer.compare(a.order, b.order));
        defs = loaded;
        synchronized (ServerStages.class) {
            recipeCacheFor = null;
        }
        if (!loaded.isEmpty()) RYScope.LOG.info("Стадий на сервере: {}", loaded.size());
    }

    private static synchronized Set<ResourceLocation> recipesOf(MinecraftServer server, StageDef def) {
        Object key = server.getRecipeManager().getOrderedRecipes();
        if (recipeCacheFor != key) {
            recipeCache = new HashMap<>();
            recipeCacheFor = key;
        }
        return recipeCache.computeIfAbsent(def, d -> {
            Set<ResourceLocation> ids = new HashSet<>();
            HolderLookup.Provider registries = server.registryAccess();
            for (RecipeHolder<?> holder : server.getRecipeManager().getOrderedRecipes()) {
                if (d.ownsRecipe(holder, registries)) ids.add(holder.id());
            }
            return ids;
        });
    }

    // --- состояние игрока ---

    public static boolean isUnlocked(ServerPlayer player, StageDef def) {
        if (grants(player.server).has(player.getUUID(), def.id)) return true;
        if (def.advancement == null) return true;
        AdvancementHolder holder = player.server.getAdvancements().get(def.advancement);
        // достижения нет в сборке: стадию открывает только команда
        return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static Set<String> unlocked(ServerPlayer player) {
        Set<String> out = new HashSet<>();
        for (StageDef def : defs) if (isUnlocked(player, def)) out.add(def.id);
        return out;
    }

    /** Отправляет клиенту описания и состояние. Клиенту без мода ничего не уходит. */
    static void sync(ServerPlayer player) {
        List<StageDef> list = defs;
        // запоминаем состояние: по нему печи игрока работают, пока он не в сети
        if (!list.isEmpty()) grants(player.server).remember(player.getUUID(), unlocked(player));
        if (!player.connection.hasChannel(StagePayload.TYPE)) return;
        List<String> ids = new ArrayList<>(), jsons = new ArrayList<>();
        for (StageDef def : list) {
            ids.add(def.id);
            jsons.add(def.json);
        }
        PacketDistributor.sendToPlayer(player, new StagePayload(ids, jsons, unlocked(player)));
    }

    private static void onAdvancement(ServerPlayer player, AdvancementHolder earned) {
        for (StageDef def : defs) {
            if (earned.id().equals(def.advancement)) {
                changed(player, def);
                return;
            }
        }
    }

    /** Состояние стадии у игрока изменилось: обновляем клиент и, если стадия скрытая, список рецептов. */
    private static void changed(ServerPlayer player, StageDef def) {
        sync(player);
        if (def.hidden) {
            // тот же пакет, что при входе: фильтр в отправке сам уберёт то, что ещё закрыто
            player.connection.send(new ClientboundUpdateRecipesPacket(player.server.getRecipeManager().getOrderedRecipes()));
        }
    }

    // --- то, ради чего всё: что игрок не получает и чего не может ---

    /** Вызывается из отправки пакетов: убирает из списка рецепты закрытых скрытых стадий. */
    public static ClientboundUpdateRecipesPacket filterPacket(ServerPlayer player, ClientboundUpdateRecipesPacket packet) {
        List<StageDef> list = defs;
        if (list.isEmpty()) return packet;
        Set<ResourceLocation> hidden = null;
        for (StageDef def : list) {
            if (!def.hidden || isUnlocked(player, def)) continue;
            if (hidden == null) hidden = new HashSet<>();
            hidden.addAll(recipesOf(player.server, def));
        }
        if (hidden == null || hidden.isEmpty()) return packet;
        Collection<RecipeHolder<?>> kept = new ArrayList<>();
        for (RecipeHolder<?> holder : packet.getRecipes()) if (!hidden.contains(holder.id())) kept.add(holder);
        return new ClientboundUpdateRecipesPacket(kept);
    }

    /** Вызывается из сетки крафта: можно ли этому игроку выполнить рецепт. */
    public static boolean isCraftLocked(ServerPlayer player, RecipeHolder<?> recipe) {
        List<StageDef> list = defs;
        if (list.isEmpty()) return false;
        for (StageDef def : list) {
            if (!def.enforce || isUnlocked(player, def)) continue;
            if (recipesOf(player.server, def).contains(recipe.id())) {
                long now = System.currentTimeMillis();
                Long last = lastNotice.get(player.getUUID());
                if (last == null || now - last > 1500) {
                    lastNotice.put(player.getUUID(), now);
                    if (player.connection.hasChannel(works.repka.ryscope.neoforge.net.StageNoticePayload.TYPE)) {
                        // клиент с модом покажет причину поверх окна верстака
                        PacketDistributor.sendToPlayer(player, new works.repka.ryscope.neoforge.net.StageNoticePayload(def.id));
                    } else {
                        player.displayClientMessage(Component.translatableWithFallback("ryscope.stage.blocked",
                            "This recipe is locked by the stage \"%s\"", def.name), true);
                    }
                }
                return true;
            }
        }
        return false;
    }

    /** Убирает из списка рецепты скрытых стадий, закрытых этому игроку (для меню, где рецепт выбирается по номеру). */
    public static <T extends net.minecraft.world.item.crafting.Recipe<?>> List<RecipeHolder<T>> filterHidden(ServerPlayer player, List<RecipeHolder<T>> recipes) {
        List<StageDef> list = defs;
        if (list.isEmpty()) return recipes;
        List<RecipeHolder<T>> out = null;
        for (StageDef def : list) {
            if (!def.hidden || isUnlocked(player, def)) continue;
            Set<ResourceLocation> ids = recipesOf(player.server, def);
            if (out == null) out = new ArrayList<>(recipes);
            out.removeIf(h -> ids.contains(h.id()));
        }
        return out == null ? recipes : out;
    }

    private static final String OWNER_KEY = "ryscope_owner";

    /** Вызывается из печи каждый тик: дёшево отвечает «нет», если стадий нет или рецепт ни к одной не относится. */
    public static boolean isSmeltLocked(net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace, RecipeHolder<?> recipe) {
        List<StageDef> list = defs;
        if (list.isEmpty() || !(furnace.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return false;
        MinecraftServer server = level.getServer();
        for (StageDef def : list) {
            if (!def.enforce || !recipesOf(server, def).contains(recipe.id())) continue;
            CompoundTag data = furnace.getPersistentData();
            // печь, которую никто не открывал (например, её кормит воронка), приписать некому: не блокируем
            if (!data.hasUUID(OWNER_KEY)) return false;
            UUID owner = data.getUUID(OWNER_KEY);
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            boolean open = player != null ? isUnlocked(player, def) : grants(server).wasUnlocked(owner, def.id);
            if (open) continue;
            if (player != null && player.containerMenu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu) notice(player, def, 5000);
            return true;
        }
        return false;
    }

    /** Костёр: еду кладёт игрок, по его стадиям и решается. Автоматика (без игрока) не блокируется. */
    public static boolean isCampfireLocked(net.minecraft.world.entity.LivingEntity entity,
                                           net.minecraft.world.level.block.entity.CampfireBlockEntity campfire,
                                           net.minecraft.world.item.ItemStack food) {
        if (defs.isEmpty() || !(entity instanceof ServerPlayer player)) return false;
        var recipe = campfire.getCookableRecipe(food);
        return recipe.isPresent() && isCraftLocked(player, recipe.get());
    }

    /** Автоверстак: решают стадии того, кто открывал его последним. */
    public static boolean isCrafterLocked(net.minecraft.world.level.block.entity.CrafterBlockEntity crafter, RecipeHolder<?> recipe) {
        if (defs.isEmpty() || !(crafter.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return false;
        MinecraftServer server = level.getServer();
        return ownerLocked(crafter, def -> recipesOf(server, def).contains(recipe.id()), net.minecraft.world.inventory.CrafterMenu.class);
    }

    /** Варочная стойка: у зелий нет рецептов с id, поэтому смотрим, относится ли результат к закрытой стадии. */
    public static boolean isBrewLocked(net.minecraft.world.level.block.entity.BrewingStandBlockEntity stand) {
        if (defs.isEmpty() || stand.getLevel() == null) return false;
        var brewing = stand.getLevel().potionBrewing();
        net.minecraft.world.item.ItemStack ingredient = stand.getItem(3);
        if (ingredient.isEmpty()) return false;
        List<net.minecraft.world.item.ItemStack> results = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            net.minecraft.world.item.ItemStack bottle = stand.getItem(i);
            if (!bottle.isEmpty() && brewing.hasMix(bottle, ingredient)) results.add(brewing.mix(ingredient, bottle));
        }
        if (results.isEmpty()) return false;
        return ownerLocked(stand, def -> {
            for (net.minecraft.world.item.ItemStack result : results) if (def.ownsItem(result)) return true;
            return false;
        }, net.minecraft.world.inventory.BrewingStandMenu.class);
    }

    /**
     * Блок, который работает сам: закрыт ли для него рецепт. Решают стадии того, кто открывал блок последним;
     * если его нет в сети, берётся состояние на момент последнего входа; если блок никто не открывал, запрета нет.
     */
    private static boolean ownerLocked(net.minecraft.world.level.block.entity.BlockEntity block,
                                       java.util.function.Predicate<StageDef> owns, Class<?> menuClass) {
        if (!(block.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return false;
        MinecraftServer server = level.getServer();
        for (StageDef def : defs) {
            if (!def.enforce || !owns.test(def)) continue;
            CompoundTag data = block.getPersistentData();
            if (!data.hasUUID(OWNER_KEY)) return false;
            UUID owner = data.getUUID(OWNER_KEY);
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            boolean open = player != null ? isUnlocked(player, def) : grants(server).wasUnlocked(owner, def.id);
            if (open) continue;
            if (player != null && menuClass.isInstance(player.containerMenu)) notice(player, def, 5000);
            return true;
        }
        return false;
    }

    private static void notice(ServerPlayer player, StageDef def, long intervalMs) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUUID());
        if (last != null && now - last <= intervalMs) return;
        lastNotice.put(player.getUUID(), now);
        if (player.connection.hasChannel(works.repka.ryscope.neoforge.net.StageNoticePayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, new works.repka.ryscope.neoforge.net.StageNoticePayload(def.id));
        } else {
            player.displayClientMessage(Component.translatableWithFallback("ryscope.stage.blocked",
                "This recipe is locked by the stage \"%s\"", def.name), true);
        }
    }

    /**
     * Замки ставятся вмешательством в код игры, и при несовпадении версии оно молча не применяется.
     * Поэтому при старте сервера проверяем, что каждое вмешательство на месте, и пишем в журнал, если нет.
     */
    private static void verifyHooks() {
        if (defs.isEmpty()) return;
        Class<?>[] targets = {
            net.minecraft.server.network.ServerCommonPacketListenerImpl.class,
            net.minecraft.world.inventory.CraftingMenu.class,
            net.minecraft.world.inventory.Slot.class,
            net.minecraft.world.inventory.SmithingMenu.class,
            net.minecraft.world.inventory.StonecutterMenu.class,
            net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.class,
            net.minecraft.world.level.block.entity.CampfireBlockEntity.class,
            net.minecraft.world.level.block.CrafterBlock.class,
            net.minecraft.world.level.block.entity.BrewingStandBlockEntity.class
        };
        List<String> missing = new ArrayList<>();
        for (Class<?> target : targets) {
            boolean found = false;
            for (java.lang.reflect.Method m : target.getDeclaredMethods()) {
                if (m.getName().contains("ryscope$")) {
                    found = true;
                    break;
                }
            }
            if (!found) missing.add(target.getSimpleName());
        }
        if (missing.isEmpty()) {
            RYScope.LOG.info("Замки стадий установлены: пакет рецептов, верстак, слот результата, кузнечный стол, камнерез, печи, костёр, автоверстак, варочная стойка");
        } else {
            RYScope.LOG.error("Замки стадий НЕ установлены в: {}. В этих местах закрытые рецепты выполняются. "
                + "Вероятно, версия игры или загрузчика отличается от той, под которую собран мод.", missing);
        }
    }

    // --- выдача командой ---

    private static Grants grants(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Grants::new, Grants::load), "ryscope_stages");
    }

    /** Стадии, выданные игрокам командой. Хранится вместе с миром. */
    private static final class Grants extends SavedData {
        private final Map<UUID, Set<String>> byPlayer = new HashMap<>();
        /** Последнее известное состояние стадий игрока: нужно, когда он не в сети. */
        private final Map<UUID, Set<String>> known = new HashMap<>();

        void remember(UUID player, Set<String> unlocked) {
            if (!unlocked.equals(known.get(player))) {
                known.put(player, new HashSet<>(unlocked));
                setDirty();
            }
        }

        boolean wasUnlocked(UUID player, String stage) {
            Set<String> set = known.get(player);
            return set != null && set.contains(stage);
        }

        boolean has(UUID player, String stage) {
            Set<String> set = byPlayer.get(player);
            return set != null && set.contains(stage);
        }

        boolean set(UUID player, String stage, boolean value) {
            Set<String> set = byPlayer.computeIfAbsent(player, k -> new HashSet<>());
            boolean changed = value ? set.add(stage) : set.remove(stage);
            if (changed) setDirty();
            return changed;
        }

        static Grants load(CompoundTag tag, HolderLookup.Provider registries) {
            Grants g = new Grants();
            for (String key : tag.getAllKeys()) {
                try {
                    Set<String> set = new HashSet<>();
                    for (Tag t : tag.getList(key, Tag.TAG_STRING)) set.add(t.getAsString());
                    g.byPlayer.put(UUID.fromString(key), set);
                } catch (IllegalArgumentException ignored) {
                    // посторонний ключ
                }
            }
            CompoundTag knownTag = tag.getCompound("known");
            for (String key : knownTag.getAllKeys()) {
                try {
                    Set<String> set = new HashSet<>();
                    for (Tag t : knownTag.getList(key, Tag.TAG_STRING)) set.add(t.getAsString());
                    g.known.put(UUID.fromString(key), set);
                } catch (IllegalArgumentException ignored) {
                    // посторонний ключ
                }
            }
            return g;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            for (Map.Entry<UUID, Set<String>> e : byPlayer.entrySet()) {
                ListTag list = new ListTag();
                for (String s : e.getValue()) list.add(StringTag.valueOf(s));
                tag.put(e.getKey().toString(), list);
            }
            CompoundTag knownTag = new CompoundTag();
            for (Map.Entry<UUID, Set<String>> e : known.entrySet()) {
                ListTag list = new ListTag();
                for (String s : e.getValue()) list.add(StringTag.valueOf(s));
                knownTag.put(e.getKey().toString(), list);
            }
            tag.put("known", knownTag);
            return tag;
        }
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        SuggestionProvider<CommandSourceStack> stageIds = (ctx, builder) -> {
            List<String> ids = new ArrayList<>();
            for (StageDef def : defs) ids.add(def.id);
            return SharedSuggestionProvider.suggest(ids, builder);
        };
        event.getDispatcher().register(Commands.literal("ryscope").requires(s -> s.hasPermission(2))
            .then(Commands.literal("stage")
                .then(Commands.literal("list").executes(ctx -> {
                    ServerPlayer self = ctx.getSource().getPlayer();
                    for (StageDef def : defs) {
                        String state = self == null ? "" : isUnlocked(self, def) ? " [open]" : " [locked]";
                        String line = def.id + " (" + def.name + ")" + state + (def.hidden ? " hidden" : "") + (def.enforce ? " enforced" : "");
                        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return defs.size();
                }))
                .then(Commands.literal("grant").then(Commands.argument("targets", EntityArgument.players())
                    .then(Commands.argument("stage", StringArgumentType.greedyString()).suggests(stageIds)
                        .executes(ctx -> setStage(ctx, true)))))
                .then(Commands.literal("revoke").then(Commands.argument("targets", EntityArgument.players())
                    .then(Commands.argument("stage", StringArgumentType.greedyString()).suggests(stageIds)
                        .executes(ctx -> setStage(ctx, false)))))));
    }

    private static int setStage(CommandContext<CommandSourceStack> ctx, boolean value) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "stage").trim();
        StageDef def = null;
        for (StageDef d : defs) if (d.id.equals(id)) def = d;
        if (def == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown stage: " + id));
            return 0;
        }
        int n = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(ctx, "targets")) {
            if (grants(player.server).set(player.getUUID(), def.id, value)) n++;
            changed(player, def);
        }
        int count = n;
        StageDef shown = def;
        ctx.getSource().sendSuccess(() -> Component.literal((value ? "Granted " : "Revoked ") + shown.id + " for " + count + " player(s)"), true);
        return n;
    }
}
