<template>
  <header class="masthead">
    <div v-if="index" class="masthead-no" aria-hidden="true">{{ index }}</div>
    <div class="masthead-main">
      <div v-if="kicker" class="masthead-kicker">
        <span class="dot" aria-hidden="true" />
        {{ kicker }}
      </div>
      <h1 class="masthead-title">{{ title }}</h1>
      <p v-if="mandate" class="masthead-mandate">{{ mandate }}</p>
    </div>
    <div class="masthead-side">
      <div class="masthead-actions">
        <slot name="actions" />
      </div>
      <div v-if="facts.length" class="masthead-facts">
        <span v-for="f in facts" :key="f.k" class="masthead-fact">
          <span class="fact-k">{{ f.k }}</span>
          <span class="fact-v">{{ f.v }}</span>
        </span>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    index?: string
    kicker?: string
    title: string
    mandate?: string
    facts?: { k: string; v: string }[]
  }>(),
  { index: '', kicker: '', mandate: '', facts: () => [] },
)
</script>
