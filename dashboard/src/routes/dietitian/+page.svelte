<script lang="ts">
  import { onMount } from 'svelte';
  import { fetchFoodLogs, createFoodLog, fetchGoals, updateGoals, parseFoodAI, type FoodLog, type DailyGoals } from '$lib/api';

  let logs: FoodLog[] = [];
  let goals: DailyGoals | null = null;
  let loading = true;
  let parsing = false;
  let error: string | null = null;

  // Manual entry state
  let mealName = '';
  let mealCalories = 0;
  let mealProtein = 0;
  let mealCarbs = 0;
  let mealFat = 0;

  // AI entry state
  let aiText = '';
  let aiImageBase64 = '';
  let fileInput: HTMLInputElement;

  async function loadData() {
    loading = true;
    error = null;
    try {
      const today = new Date().toISOString().split('T')[0];
      const [logsRes, goalsRes] = await Promise.all([
        fetchFoodLogs(today),
        fetchGoals(today)
      ]);
      logs = logsRes.logs;
      goals = goalsRes.goals;
    } catch (e: any) {
      error = e.message;
    } finally {
      loading = false;
    }
  }

  async function handleManualSubmit() {
    if (!mealName || mealCalories <= 0) {
      error = 'Name and calories are required.';
      return;
    }
    
    try {
      await createFoodLog({
        name: mealName,
        calories: mealCalories,
        protein_g: mealProtein,
        carbs_g: mealCarbs,
        fat_g: mealFat,
        source: 'manual',
        logged_at: Date.now()
      });
      // reset form
      mealName = ''; mealCalories = 0; mealProtein = 0; mealCarbs = 0; mealFat = 0;
      await loadData();
    } catch (e: any) {
      error = e.message;
    }
  }

  async function handleAILogging() {
    if (!aiText && !aiImageBase64) return;
    
    parsing = true;
    error = null;
    try {
      const res = await parseFoodAI(aiText, aiImageBase64);
      if (res.ok && res.result) {
        const aiData = res.result;
        await createFoodLog({
          name: aiData.name,
          calories: aiData.calories,
          protein_g: aiData.protein_g,
          carbs_g: aiData.carbs_g,
          fat_g: aiData.fat_g,
          micros: aiData.micros,
          source: aiImageBase64 ? 'ai_photo' : 'ai_text',
          meta: { explanation: aiData.explanation },
          logged_at: Date.now()
        });
        aiText = '';
        aiImageBase64 = '';
        if (fileInput) fileInput.value = '';
        await loadData();
      }
    } catch (e: any) {
      error = e.message;
    } finally {
      parsing = false;
    }
  }

  function handleImageUpload(e: Event) {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (ev) => {
      aiImageBase64 = ev.target?.result as string;
    };
    reader.readAsDataURL(file);
  }

  async function setMockGoals() {
    try {
      await updateGoals({
        target_date: new Date().toISOString().split('T')[0],
        target_calories: 2000,
        target_protein_g: 150,
        target_carbs_g: 200,
        target_fat_g: 65
      });
      await loadData();
    } catch (e: any) {
      error = e.message;
    }
  }

  onMount(() => {
    loadData();
  });

  // Calculate totals
  $: totalCals = logs.reduce((sum, log) => sum + log.calories, 0);
  $: totalProtein = logs.reduce((sum, log) => sum + (log.protein_g || 0), 0);
  $: totalCarbs = logs.reduce((sum, log) => sum + (log.carbs_g || 0), 0);
  $: totalFat = logs.reduce((sum, log) => sum + (log.fat_g || 0), 0);
</script>

<div class="space-y-6 animate-fade-in pb-16">
  <div class="flex justify-between items-center">
    <div>
      <h1 class="text-3xl font-extrabold bg-clip-text text-transparent bg-gradient-to-r from-accent to-secondary">Dietitian AI</h1>
      <p class="text-sm text-base-content/60">Log your meals & track nutrition</p>
    </div>
  </div>

  {#if error}
    <div class="alert alert-error">
      <span>{error}</span>
    </div>
  {/if}

  <!-- Goals & Progress -->
  <div class="card bg-base-200/60 shadow-xl border border-accent/20">
    <div class="card-body p-6">
      <h2 class="card-title text-accent">Today's Progress</h2>
      
      {#if !goals}
        <button class="btn btn-outline btn-sm mt-2" on:click={setMockGoals}>Set Default Goals (2000 kcal)</button>
      {:else}
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4">
          <!-- Calories -->
          <div class="flex flex-col gap-1">
            <span class="text-xs uppercase text-base-content/60 font-bold">Calories</span>
            <div class="flex justify-between text-sm">
              <span>{Math.round(totalCals)}</span>
              <span class="text-base-content/50">/ {goals.target_calories}</span>
            </div>
            <progress class="progress progress-accent w-full" value={totalCals} max={goals.target_calories}></progress>
          </div>
          <!-- Protein -->
          <div class="flex flex-col gap-1">
            <span class="text-xs uppercase text-base-content/60 font-bold">Protein (g)</span>
            <div class="flex justify-between text-sm">
              <span>{Math.round(totalProtein)}</span>
              <span class="text-base-content/50">/ {goals.target_protein_g}</span>
            </div>
            <progress class="progress progress-secondary w-full" value={totalProtein} max={goals.target_protein_g}></progress>
          </div>
          <!-- Carbs -->
          <div class="flex flex-col gap-1">
            <span class="text-xs uppercase text-base-content/60 font-bold">Carbs (g)</span>
            <div class="flex justify-between text-sm">
              <span>{Math.round(totalCarbs)}</span>
              <span class="text-base-content/50">/ {goals.target_carbs_g}</span>
            </div>
            <progress class="progress progress-info w-full" value={totalCarbs} max={goals.target_carbs_g}></progress>
          </div>
          <!-- Fat -->
          <div class="flex flex-col gap-1">
            <span class="text-xs uppercase text-base-content/60 font-bold">Fat (g)</span>
            <div class="flex justify-between text-sm">
              <span>{Math.round(totalFat)}</span>
              <span class="text-base-content/50">/ {goals.target_fat_g}</span>
            </div>
            <progress class="progress progress-warning w-full" value={totalFat} max={goals.target_fat_g}></progress>
          </div>
        </div>
      {/if}
    </div>
  </div>

  <div class="grid md:grid-cols-2 gap-6">
    <!-- Log new meal -->
    <div class="card bg-base-200/60 shadow-xl border border-secondary/20 h-fit">
      <div class="card-body p-6">
        <h2 class="card-title text-secondary mb-4">Log Meal</h2>
        
        <div class="tabs tabs-boxed bg-base-300/50 mb-4">
          <a class="tab tab-active">AI Log</a>
          <a class="tab">Manual</a>
        </div>

        <!-- AI Logging Form -->
        <div class="form-control w-full gap-4">
          <div>
            <label class="label"><span class="label-text">Describe your meal</span></label>
            <textarea class="textarea textarea-bordered w-full" placeholder="e.g. 2 scrambled eggs, a slice of toast with butter..." bind:value={aiText}></textarea>
          </div>
          
          <div class="divider">OR</div>
          
          <div>
            <label class="label"><span class="label-text">Upload a Photo</span></label>
            <input type="file" accept="image/*" class="file-input file-input-bordered file-input-secondary w-full" bind:this={fileInput} on:change={handleImageUpload} />
            {#if aiImageBase64}
              <div class="mt-2 text-xs text-success">Image ready for analysis!</div>
            {/if}
          </div>

          <button class="btn btn-secondary mt-2" on:click={handleAILogging} disabled={parsing || (!aiText && !aiImageBase64)}>
            {#if parsing}
              <span class="loading loading-spinner"></span> Analyzing via Gemini...
            {:else}
              Analyze & Log
            {/if}
          </button>
        </div>
      </div>
    </div>

    <!-- Today's Log -->
    <div class="card bg-base-200/60 shadow-xl border border-primary/20">
      <div class="card-body p-6">
        <h2 class="card-title text-primary mb-4">Today's Log</h2>
        
        {#if loading}
          <span class="loading loading-spinner text-primary"></span>
        {:else if logs.length === 0}
          <p class="text-base-content/50 italic">No meals logged today.</p>
        {:else}
          <div class="space-y-4">
            {#each logs as log}
              <div class="bg-base-300/50 p-4 rounded-lg flex flex-col gap-2">
                <div class="flex justify-between items-start">
                  <div>
                    <h3 class="font-bold text-lg">{log.name}</h3>
                    <span class="text-xs text-base-content/50">{new Date(log.logged_at).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})} • Source: {log.source}</span>
                  </div>
                  <div class="badge badge-primary">{Math.round(log.calories)} kcal</div>
                </div>
                
                <div class="flex gap-4 text-sm mt-1">
                  <span class="text-secondary font-medium">P: {log.protein_g}g</span>
                  <span class="text-info font-medium">C: {log.carbs_g}g</span>
                  <span class="text-warning font-medium">F: {log.fat_g}g</span>
                </div>
                
                {#if log.meta?.explanation}
                  <p class="text-xs italic text-base-content/60 mt-1">AI Note: {log.meta.explanation}</p>
                {/if}
              </div>
            {/each}
          </div>
        {/if}
      </div>
    </div>
  </div>
</div>