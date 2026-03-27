<template>
  <div v-if="visible && visibleChapters.length > 0" class="sticky-chapter-indicator" :style="indicatorStyle">
    <span class="primary">{{ visibleChapters[0].label }}</span>
    <template v-if="visibleChapters[1]">
      <span class="secondary">, {{ visibleChapters[1].label }}</span>
    </template>
  </div>
</template>

<script setup lang="ts">
import {computed} from "vue";
import type {VisibleChapter} from "@/composables/visible-chapters-indicator";

const props = defineProps<{
  visible: boolean,
  visibleChapters: VisibleChapter[],
  bottomOffset: number,
  leftOffset: number,
}>();

const indicatorStyle = computed(() => ({
    bottom: `${Math.max(0, props.bottomOffset)}px`,
    left: `${Math.max(0, props.leftOffset)}px`,
}));
</script>

<style scoped lang="scss">
.sticky-chapter-indicator {
  position: fixed;
  transform: none;
  z-index: 30;
  padding: 4px 12px;
  border-radius: 0 12px 0 0;
  background: var(--background-color);
  color: var(--text-color);
  border-top: 1px solid var(--text-color);
  border-right: 1px solid var(--text-color);
  font-size: 0.85em;
  line-height: 1.2;
  pointer-events: none;
  max-width: min(90vw, 40rem);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.primary {
  font-weight: 600;
}

.secondary {
  opacity: 0.7;
}
</style>
