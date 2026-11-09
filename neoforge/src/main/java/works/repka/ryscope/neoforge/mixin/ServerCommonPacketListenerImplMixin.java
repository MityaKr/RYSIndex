package works.repka.ryscope.neoforge.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import works.repka.ryscope.neoforge.ServerStages;

/**
 * Список рецептов уходит всем игрокам одним и тем же пакетом. Здесь он подменяется на свой для каждого игрока:
 * без рецептов скрытых стадий, которые этому игроку ещё закрыты. Работает и для клиентов без мода.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @ModifyVariable(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Packet<?> ryscope$filterRecipes(Packet<?> packet) {
        if (packet instanceof ClientboundUpdateRecipesPacket recipes && (Object) this instanceof ServerGamePacketListenerImpl game) {
            return ServerStages.filterPacket(game.player, recipes);
        }
        return packet;
    }
}
