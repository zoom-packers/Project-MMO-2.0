package harmonised.pmmo.setup.datagen;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import cpw.mods.modlauncher.api.LamdbaExceptionUtils;
import harmonised.pmmo.features.loot_modifiers.RareDropModifier;
import harmonised.pmmo.features.loot_modifiers.SkillLootConditionKill;
import harmonised.pmmo.features.loot_modifiers.SkillLootConditionPlayer;
import harmonised.pmmo.features.loot_modifiers.TreasureLootModifier;
import harmonised.pmmo.features.loot_modifiers.ValidBlockCondition;
import harmonised.pmmo.util.Reference;
import harmonised.pmmo.util.RegistryUtil;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemKilledByPlayerCondition;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.conditions.WithConditions;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootTableIdCondition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public abstract class GLMProvider implements DataProvider {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final PackOutput output;
	private final String modid = Reference.MOD_ID;
	private final Map<String, JsonElement> toSerialize = new HashMap<>();
	private boolean replace = false;
	private Path destination;

	public GLMProvider(PackOutput gen, Path destination) {
		this.output = gen;
		this.destination = destination;
	}



	protected abstract void start();

	public TreasureLootModifier of(TagKey<Block> validBlocks, Item drop, int count, double chance, String skill, int minLevel) {
		return new TreasureLootModifier(
				new LootItemCondition[] {
					new SkillLootConditionPlayer(minLevel, Integer.MAX_VALUE, skill),
					new ValidBlockCondition(validBlocks)
				}, RegistryUtil.getId(drop), count, chance);
	}

	public TreasureLootModifier of(Block validBlocks, Item drop, int count, double chance, String skill, int minLevel) {
		return new TreasureLootModifier(
				new LootItemCondition[] {
					new SkillLootConditionPlayer(minLevel, Integer.MAX_VALUE, skill),
					new ValidBlockCondition(validBlocks)
				}, RegistryUtil.getId(drop), count, chance);
	}

	/**Used to specify that a tag member should drop extra of itself.
	 * If not using tags, {@link #of(Block, Item, int, double, String, int) of()}
	 * should be used since this is a one-to-one relationship.
	 *
	 * @param validBlocks the tag containing all applicable blocks
	 * @param count how many of the self should drop
	 * @param chance probablity of the extra drop
	 * @param skill skill associated with this extra drop
	 * @param minLevel level required to enable the extra drop
	 * @return modifier to be generated
	 */
	public TreasureLootModifier extra(TagKey<Block> validBlocks, int count, double chance, String skill, int minLevel) {
		return new TreasureLootModifier(
				new LootItemCondition[] {
					new SkillLootConditionPlayer(minLevel, Integer.MAX_VALUE, skill),
					new ValidBlockCondition(validBlocks)
				}, RegistryUtil.getId(Blocks.AIR), count, chance);
	}

	public RareDropModifier fish(Item drop, int count, double chance, String skill, int minLevel, int maxLevel) {
		return new RareDropModifier(
				new LootItemCondition[] {
						LootTableIdCondition.builder(BuiltInLootTables.FISHING).build(),
						new SkillLootConditionKill(minLevel, maxLevel, skill)
				}, RegistryUtil.getId(drop), count, chance);
	}

	public RareDropModifier fish(Item drop, int count, double chance, String skill, int minLevel) {
		return fish(drop, count, chance, skill, minLevel, Integer.MAX_VALUE);
	}

	public RareDropModifier mob(EntityType<?> mob, Item drop, int count, double chance, String skill, int minLevel, int maxLevel) {
		return new RareDropModifier(
				new LootItemCondition[] {
						LootItemKilledByPlayerCondition.killedByPlayer().build(),
						LootTableIdCondition.builder(mob.getDefaultLootTable()).build(),
						new SkillLootConditionKill(minLevel, maxLevel, skill)
				}, RegistryUtil.getId(drop), count, chance);
	}
	
	public RareDropModifier mob(EntityType<?> mob, Item drop, int count, double chance, String skill, int minLevel) {
		return mob(mob, drop, count, chance, skill, minLevel, Integer.MAX_VALUE);
	}

	@Override
	public CompletableFuture<?> run(CachedOutput cache) {
		start();

		Path forgePath = output.getOutputFolder().resolve(destination).resolve("loot_modifiers").resolve("global_loot_modifiers.json");
		Path modifierFolderPath = output.getOutputFolder().resolve(destination).resolve(this.modid).resolve("loot_modifiers");
		List<ResourceLocation> entries = new ArrayList<>();

		ImmutableList.Builder<CompletableFuture<?>> futuresBuilder = new ImmutableList.Builder<>();

		toSerialize.forEach(LamdbaExceptionUtils.rethrowBiConsumer((name, json) -> {
			entries.add(new ResourceLocation(modid, name));
			Path modifierPath = modifierFolderPath.resolve(name + ".json");
			futuresBuilder.add(DataProvider.saveStable(cache, json, modifierPath));
		}));

		JsonObject forgeJson = new JsonObject();
		forgeJson.addProperty("replace", this.replace);
		forgeJson.add("entries", GSON.toJsonTree(entries.stream().map(ResourceLocation::toString).collect(Collectors.toList())));

		futuresBuilder.add(DataProvider.saveStable(cache, forgeJson, forgePath));

		return CompletableFuture.allOf(futuresBuilder.build().toArray(CompletableFuture[]::new));
	}

	/**
	 * Passes in the data needed to create the file without any extra objects.
	 *
	 * @param modifier   the name of the modifier, which will be the file name
	 * @param instance   the instance to serialize
	 * @param conditions a list of conditions to add to the GLM file
	 */
	public <T extends IGlobalLootModifier> void add(String modifier, T instance, List<ICondition> conditions) {
		JsonElement json = IGlobalLootModifier.CONDITIONAL_CODEC.encodeStart(JsonOps.INSTANCE, Optional.of(new WithConditions<>(conditions, instance))).getOrThrow(false, s -> {});
		this.toSerialize.put(modifier, json);
	}

	/**
	 * Passes in the data needed to create the file without any extra objects.
	 *
	 * @param modifier   the name of the modifier, which will be the file name
	 * @param instance   the instance to serialize
	 * @param conditions a list of conditions to add to the GLM file
	 */
	public <T extends IGlobalLootModifier> void add(String modifier, T instance, ICondition... conditions) {
		add(modifier, instance, Arrays.asList(conditions));
	}

	@Override
	public String getName() {
		return "Global Loot Modifiers : " + destination.toString();
	}
}
