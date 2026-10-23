package dev.mcsionna;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Transmitter / receiver block: placing or removing one re-runs the simulation around it. */
public class RadioBlock extends Block {
	public RadioBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (level instanceof ServerLevel server && !oldState.is(this)) {
			Simulation.autoRun(server, pos);
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		Simulation.autoRun(level, pos);
	}
}
