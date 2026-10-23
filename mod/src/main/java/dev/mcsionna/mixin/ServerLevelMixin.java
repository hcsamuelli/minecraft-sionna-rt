package dev.mcsionna.mixin;

import dev.mcsionna.Simulation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every server-side block change that players can see goes through sendBlockUpdated. */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void mcSionna$blockChanged(BlockPos pos, BlockState old, BlockState current, int flags, CallbackInfo ci) {
		// only changes the simulation can see: a different block, or water appearing/disappearing
		// (not redstone power, crop growth, ... which would re-run twice a second forever)
		if (old.getBlock() != current.getBlock() || old.getFluidState().is(FluidTags.WATER) != current.getFluidState().is(FluidTags.WATER)) {
			Simulation.onBlockChanged((ServerLevel) (Object) this, pos);
		}
	}
}
