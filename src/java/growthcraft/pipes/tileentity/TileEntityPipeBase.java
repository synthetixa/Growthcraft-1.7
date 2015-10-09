package growthcraft.pipes.tileentity;

import growthcraft.pipes.utils.PipeType;
import growthcraft.pipes.utils.PipeFlag;
import growthcraft.pipes.block.IPipeBlock;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

public class TileEntityPipeBase extends TileEntity implements IFluidHandler, IPipeTile
{
	enum UsageState
	{
		UNUSABLE,
		USABLE;
	}

	enum TransferState
	{
		IDLE,
		INPUT,
		OUTPUT;
	}

	class PipeFluidTank extends FluidTank
	{
		public PipeFluidTank(int capacity)
		{
			super(capacity);
		}

		public boolean isFull()
		{
			return getFluidAmount() >= getCapacity();
		}

		public boolean hasFluid()
		{
			return getFluidAmount() > 0;
		}

		public boolean isEmpty()
		{
			return getFluidAmount() == 0;
		}

		public int getFreeSpace()
		{
			return getCapacity() - getFluidAmount();
		}

		public int getDrainAmount()
		{
			return Math.min(getFluidAmount(), 10);
		}

		public int getFillAmount()
		{
			return Math.min(getFreeSpace(), 10);
		}
	}

	class PipeSection
	{
		public TransferState transferState = TransferState.IDLE;
		public UsageState usageState = UsageState.UNUSABLE;
		public int feedFlag = 0;
	}

	class PipeBuffer
	{
		public TileEntity te = null;

		public void clear()
		{
			this.te = null;
		}
	}

	public PipeSection[] pipeSections = new PipeSection[7];
	public PipeBuffer[] pipeBuffers = new PipeBuffer[ForgeDirection.VALID_DIRECTIONS.length];
	public int color;
	private PipeFluidTank fluidTank = new PipeFluidTank(FluidContainerRegistry.BUCKET_VOLUME / 4);
	private int pipeRenderState = PipeFlag.PIPE_CORE;
	private boolean dirty = true;
	private boolean needsUpdate = true;
	private PipeType pipeType = PipeType.UNKNOWN;

	public TileEntityPipeBase()
	{
		for (int i = 0; i < pipeSections.length; ++i)
		{
			pipeSections[i] = new PipeSection();
		}
		for (int i = 0; i < pipeBuffers.length; ++i)
		{
			pipeBuffers[i] = new PipeBuffer();
		}
	}

	public void onNeighbourChanged()
	{
		dirty = true;
	}

	@Override
	public void invalidate()
	{
		super.invalidate();
		dirty = true;
	}

	public boolean isVacuumPipe()
	{
		return pipeType == PipeType.VACUUM;
	}

	public int getPipeCoreState()
	{
		if (isVacuumPipe())
		{
			return PipeFlag.PIPE_VACUUM_CORE;
		}
		return PipeFlag.PIPE_CORE;
	}

	public void refreshCache()
	{
		needsUpdate = true;
		final Block block = worldObj.getBlock(xCoord, yCoord, zCoord);
		if (block instanceof IPipeBlock)
		{
			pipeType = ((IPipeBlock)block).getPipeType();
		}
		else
		{
			invalidate();
			return;
		}
		pipeRenderState = getPipeCoreState();
		for (int i = 0; i < pipeBuffers.length; ++i)
		{
			final ForgeDirection dir = ForgeDirection.getOrientation(i);
			final TileEntity te = worldObj.getTileEntity(xCoord + dir.offsetX, yCoord + dir.offsetY, zCoord + dir.offsetZ);
			pipeBuffers[i].te = te;
			pipeSections[i].usageState = UsageState.UNUSABLE;
			if (te instanceof IPipeTile)
			{
				pipeRenderState |= 1 << i;
				pipeSections[i].usageState = UsageState.USABLE;
			}
			else if (te instanceof IFluidHandler)
			{
				pipeRenderState |= 1 << (i + 6);
				pipeSections[i].usageState = UsageState.USABLE;
			}
		}
	}

	private void transferFluid(IFluidHandler dest, PipeFluidTank src, ForgeDirection dir)
	{
		FluidStack drained = fluidTank.drain(fluidTank.getDrainAmount(), true);
		if (drained != null)
		{
			int filled = dest.fill(dir, drained, true);
			int diff = drained.amount - filled;
			if (diff > 0)
			{
				src.fill(new FluidStack(drained.getFluid(), diff), true);
			}
		}
	}

	private void transferFluid(PipeFluidTank dest, IFluidHandler src, ForgeDirection dir)
	{
		FluidStack drained = src.drain(dir, dest.getFillAmount(), true);
		if (drained != null)
		{
			int filled = dest.fill(drained, true);
			int diff = drained.amount - filled;
			if (diff > 0)
			{
				src.fill(dir, new FluidStack(drained.getFluid(), diff), true);
			}
		}
	}

	@Override
	public void updateEntity()
	{
		if (!worldObj.isRemote)
		{
			if (dirty)
			{
				dirty = false;
				refreshCache();
			}
		}

		final PipeSection coreSection = pipeSections[6];
		coreSection.feedFlag = 0;
		for (int i = 0; i < pipeBuffers.length; ++i)
		{
			final ForgeDirection dir = ForgeDirection.getOrientation(i);
			final ForgeDirection oppdir = dir.getOpposite();
			final PipeSection section = pipeSections[i];
			if (section.usageState == UsageState.USABLE)
			{
				final TileEntity te = pipeBuffers[i].te;
				if (section.transferState == TransferState.OUTPUT)
				{
					if (isVacuumPipe())
					{
						if (te instanceof IPipeTile)
						{
							if (fluidTank.getFluidAmount() > 0)
							{
								final IFluidHandler fluidHandler = (IFluidHandler)te;
								transferFluid(fluidHandler, fluidTank, oppdir);
							}
						}
					}
					else
					{
						if (te instanceof IFluidHandler)
						{
							if (fluidTank.getFluidAmount() > 0)
							{
								final IFluidHandler fluidHandler = (IFluidHandler)te;
								transferFluid(fluidHandler, fluidTank, oppdir);
							}
						}
					}
					section.transferState = TransferState.IDLE;
				}
				else
				{
					if (section.transferState == TransferState.INPUT)
					{
						coreSection.feedFlag = 1 << i;
						section.transferState = TransferState.IDLE;
					}
					if (isVacuumPipe())
					{
						if (te instanceof IFluidHandler && !(te instanceof IPipeTile))
						{
							final IFluidHandler fluidHandler = (IFluidHandler)te;
							if (!fluidTank.isFull())
							{
								transferFluid(fluidTank, fluidHandler, oppdir);
								section.transferState = TransferState.INPUT;
							}
						}
					}
				}
			}
		}

		if (fluidTank.getFluidAmount() > 0)
		{
			int dist = 0;
			for (int i = 0; i < pipeBuffers.length; ++i)
			{
				final int flag = 1 << i;
				if ((coreSection.feedFlag & flag) == flag) continue;
				if (pipeSections[i].usageState == UsageState.USABLE)
					dist += 1;
			}

			if (dist != 0)
			{
				for (int i = 0; i < pipeBuffers.length; ++i)
				{
					final int flag = 1 << i;
					if ((coreSection.feedFlag & flag) == flag) continue;
					if (pipeSections[i].usageState == UsageState.USABLE)
					{
						final PipeSection section = pipeSections[i];
						section.transferState = TransferState.OUTPUT;
					}
				}
			}
		}

		if (needsUpdate)
		{
			needsUpdate = false;
			this.worldObj.markBlockForUpdate(this.xCoord, this.yCoord, this.zCoord);
		}
	}

	public boolean canFill(ForgeDirection from, Fluid fluid)
	{
		return true;
	}

	public int fill(ForgeDirection from, FluidStack resource, boolean doFill)
	{
		final int amount = fluidTank.fill(resource, doFill);
		if (doFill)
		{
			if (from != ForgeDirection.UNKNOWN)
			{
				pipeSections[from.ordinal()].transferState = TransferState.INPUT;
			}
		}
		return amount;
	}

	public boolean canDrain(ForgeDirection from, Fluid fluid)
	{
		return true;
	}

	public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain)
	{
		final FluidStack stack = fluidTank.drain(maxDrain, doDrain);
		if (doDrain)
		{
			if (from != ForgeDirection.UNKNOWN)
			{
				pipeSections[from.ordinal()].transferState = TransferState.OUTPUT;
			}
		}
		return stack;
	}

	public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain)
	{
		if (resource == null || !resource.isFluidEqual(fluidTank.getFluid()))
		{
			return null;
		}
		return drain(from, resource.amount, doDrain);
	}

	public FluidTankInfo[] getTankInfo(ForgeDirection from)
	{
		return new FluidTankInfo[]{ fluidTank.getInfo() };
	}

	public int getPipeRenderState()
	{
		return pipeRenderState;
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt)
	{
		super.readFromNBT(nbt);
		this.pipeRenderState = nbt.getInteger("pipe_render_state");
		if (nbt.hasKey("tank"))
		{
			fluidTank.readFromNBT(nbt.getCompoundTag("tank"));
		}
	}

	@Override
	public void writeToNBT(NBTTagCompound nbt)
	{
		super.writeToNBT(nbt);
		nbt.setInteger("pipe_render_state", getPipeRenderState());
		NBTTagCompound tag = new NBTTagCompound();
		fluidTank.writeToNBT(tag);
		nbt.setTag("tank", tag);
	}

	/************
	 * PACKETS
	 ************/
	@Override
	public Packet getDescriptionPacket()
	{
		NBTTagCompound nbtTag = new NBTTagCompound();
		this.writeToNBT(nbtTag);
		return new S35PacketUpdateTileEntity(this.xCoord, this.yCoord, this.zCoord, 1, nbtTag);
	}

	@Override
	public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity packet)
	{
		this.readFromNBT(packet.func_148857_g());
		this.worldObj.func_147479_m(this.xCoord, this.yCoord, this.zCoord);
	}
}
