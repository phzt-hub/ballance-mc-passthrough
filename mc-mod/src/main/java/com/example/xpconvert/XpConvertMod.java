package com.example.xpconvert;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XpConvertMod implements ModInitializer {
	public static final String MOD_ID = "xpconvert";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("XP Convert Mod loaded: right-click enchanting table with glass bottle to convert XP");

		UseBlockCallback.EVENT.register(XpConvertMod::onUseBlock);
	}

	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
		// Must run on server side only
		if (level.isClientSide()) {
			return InteractionResult.PASS;
		}

		BlockPos pos = hitResult.getBlockPos();
		BlockState state = level.getBlockState(pos);

		// Only care about enchanting tables
		if (!state.is(Blocks.ENCHANTING_TABLE)) {
			return InteractionResult.PASS;
		}

		// Player must be holding a glass bottle
		ItemStack held = player.getItemInHand(hand);
		if (held.isEmpty() || !held.is(Items.GLASS_BOTTLE)) {
			// Not holding bottle -> open enchanting table UI normally
			return InteractionResult.PASS;
		}

		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		ServerLevel serverLevel = (ServerLevel) level;

		// Check: at least one lapis lazuli in inventory
		ItemStack lapisProbe = new ItemStack(Items.LAPIS_LAZULI);
		if (!serverPlayer.getInventory().contains(lapisProbe)) {
			serverPlayer.sendSystemMessage(Component.literal("Need at least one lapis lazuli to convert XP"));
			return InteractionResult.FAIL;
		}

		// Check: at least 1 experience level
		if (serverPlayer.experienceLevel < 1) {
			serverPlayer.sendSystemMessage(Component.literal("Need at least 1 experience level to convert XP"));
			return InteractionResult.FAIL;
		}

		// Consume: 1 glass bottle, 1 lapis, 1 level
		held.shrink(1);
		serverPlayer.getInventory().removeItem(lapisProbe);
		serverPlayer.giveExperienceLevels(-1);

		// Produce: 1 bottle o' enchanting
		ItemStack result = new ItemStack(Items.EXPERIENCE_BOTTLE);
		if (!serverPlayer.getInventory().add(result)) {
			// Inventory full: drop at player's feet
			ItemEntity drop = serverPlayer.drop(result, false, net.minecraft.util.Prediction.PREDICTED);
			if (drop != null) {
				drop.setNoPickUpDelay();
			}
		}

		// Feedback: enchanting table sound
		serverLevel.playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);

		LOGGER.info("Player {} converted 1 level of XP into a bottle o' enchanting at {}",
				serverPlayer.getName().getString(), pos);
		return InteractionResult.SUCCESS;
	}
}
