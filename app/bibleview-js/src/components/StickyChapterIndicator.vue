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
  topOffset: number,
}>();

const indicatorStyle = computed(() => ({
    top: `${Math.max(0, props.topOffset)}px`
}));
</script>

<style scoped lang="scss">
.sticky-chapter-indicator {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  z-index: 30;
  padding: 4px 12px;
  border-radius: 12px;
  background: hsla(var(--text-color-h), var(--text-color-s), var(--text-color-l), 0.85);
  color: var(--background-color);
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
