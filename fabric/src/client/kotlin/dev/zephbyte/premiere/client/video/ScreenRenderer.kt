package dev.zephbyte.premiere.client.video

import com.mojang.blaze3d.vertex.PoseStack
import dev.zephbyte.premiere.client.ClientScreens
import dev.zephbyte.premiere.client.PremiereClientConfig
import dev.zephbyte.premiere.geo.ScreenFacing
import dev.zephbyte.premiere.screen.ScreenDefinition
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.resources.Identifier
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture

/**
 * Draws each active screen as a single textured quad floating a hair in front
 * of the (vanilla, untouched) wall blocks. Video is fitted inside the wall
 * rectangle preserving aspect, centered, letterboxed by the wall itself.
 */
object ScreenRenderer {

    private const val FULL_BRIGHT = 0xF000F0
    private const val EPS = 0.005f

    // Snapshotted during extraction (which has level access); read during the
    // render pass, which must not touch the client level.
    @Volatile
    private var currentDimension: String? = null

    fun register() {
        LevelRenderEvents.END_EXTRACTION.register { context ->
            currentDimension = context.level().dimension().identifier().toString()
        }
        LevelRenderEvents.COLLECT_SUBMITS.register(::collect)
    }

    private fun collect(context: LevelRenderContext) {
        ClientScreens.drainRetired { it.destroyTexture() }

        val camera = context.levelState().cameraRenderState
        if (!camera.initialized) return
        val dimension = currentDimension ?: return

        for (active in ClientScreens.renderable()) {
            val player = active.player ?: continue
            val definition = active.definition
            if (definition.dimension != dimension) continue
            val textureId = player.textureForRender() ?: continue

            // Fit the video inside the wall, preserving aspect.
            val videoAspect = player.aspect
            val wallAspect = definition.width.toFloat() / definition.height
            val quadWidth: Float
            val quadHeight: Float
            if (videoAspect >= wallAspect) {
                quadWidth = definition.width.toFloat()
                quadHeight = quadWidth / videoAspect
            } else {
                quadHeight = definition.height.toFloat()
                quadWidth = quadHeight * videoAspect
            }

            val poseStack = context.poseStack()
            poseStack.pushPose()
            val cameraPos = camera.pos
            poseStack.translate(
                definition.origin.x - cameraPos.x,
                definition.origin.y - cameraPos.y,
                definition.origin.z - cameraPos.z,
            )

            // Cinema letterbox: a black quad covering the whole wall, a hair
            // behind the picture, so unused screen area reads as screen
            // rather than as wall. (The video texture multiplied by black is
            // pure black — no extra pipeline needed.)
            val letterbox = PremiereClientConfig.letterboxBlack &&
                (quadWidth < definition.width || quadHeight < definition.height)
            if (letterbox) {
                submitQuad(
                    context, poseStack, textureId, definition,
                    definition.width.toFloat(), definition.height.toFloat(),
                    eps = EPS * 0.5f, red = 0, green = 0, blue = 0,
                )
            }
            submitQuad(context, poseStack, textureId, definition, quadWidth, quadHeight, EPS, 255, 255, 255)

            poseStack.popPose()
        }
    }

    /**
     * One centered quad on the wall face, [eps] blocks out from it, tinted
     * [red]/[green]/[blue]. Local coords are relative to the wall origin;
     * "left" is the viewer's left looking at the face.
     */
    private fun submitQuad(
        context: LevelRenderContext,
        poseStack: PoseStack,
        textureId: Identifier,
        definition: ScreenDefinition,
        quadWidth: Float,
        quadHeight: Float,
        eps: Float,
        red: Int,
        green: Int,
        blue: Int,
    ) {
        context.submitNodeCollector().submitCustomGeometry(
            poseStack,
            RenderTypes.entityTranslucentEmissive(textureId),
        ) { pose, consumer ->
            val yCenter = definition.height / 2f
            val yTop = yCenter + quadHeight / 2f
            val yBottom = yCenter - quadHeight / 2f
            val spanCenter = definition.width / 2f
            val spanLo = spanCenter - quadWidth / 2f
            val spanHi = spanCenter + quadWidth / 2f

            // (x, z) of the u=0 (viewer-left) and u=1 edges, plus the face plane.
            val (left, right, plane) = when (definition.facing) {
                ScreenFacing.SOUTH -> Triple(spanLo, spanHi, 1f + eps)
                ScreenFacing.NORTH -> Triple(spanHi, spanLo, -eps)
                ScreenFacing.EAST -> Triple(spanHi, spanLo, 1f + eps)
                ScreenFacing.WEST -> Triple(spanLo, spanHi, -eps)
            }
            val onX = definition.facing.spansX

            val normalX = definition.facing.stepX.toFloat()
            val normalZ = definition.facing.stepZ.toFloat()

            fun vertex(span: Float, y: Float, u: Float, v: Float) {
                val x = if (onX) span else plane
                val z = if (onX) plane else span
                consumer.addVertex(pose, x, y, z)
                    .setColor(red, green, blue, 255)
                    .setUv(u, v)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(FULL_BRIGHT)
                    .setNormal(pose, normalX, 0f, normalZ)
            }

            // Counter-clockwise as seen from the front.
            vertex(left, yTop, 0f, 0f)
            vertex(left, yBottom, 0f, 1f)
            vertex(right, yBottom, 1f, 1f)
            vertex(right, yTop, 1f, 0f)
        }
    }
}
