package dev.xshirae.neoplayersync

import net.minecraft.nbt.CompoundTag
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.storage.TagValueOutput

internal fun Entity.saveEntityForTest(): CompoundTag {
    val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level().registryAccess())
    saveWithoutId(output)
    return output.buildResult()
}

