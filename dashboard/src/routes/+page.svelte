<script lang="ts">
  import { onMount, onDestroy } from 'svelte';
  import uPlot from 'uplot';
  import { fetchSummary, fetchSamples, fetchWorkouts, type Summary, type Sample, type Workout } from '$lib/api';

  let summary: Summary | null = null;
  let error: string | null = null;
  let loading = true;

  // Chart state
  let chartEl: HTMLDivElement;
  let chart: uPlot | null = null;
  let hrSamples: Sample[] = [];
  let recentWorkouts: Workout[] = [];
  
  // Filters
  let timeRange: '24h' | '7d' | '30d' = '24h';

  async function load() {
    loading = true;
    error = null;
    try {
      summary = await fetchSummary();
      
      const now = Date.now();
      let from = now;
      if (timeRange === '24h') from = now - 24 * 3600 * 1000;
      else if (timeRange === '7d') from = now - 7 * 24 * 3600 * 1000;
      else if (timeRange === '30d') from = now - 30 * 24 * 3600 * 1000;

      const [samplesRes, workoutsRes] = await Promise.all([
        fetchSamples('heart_rate', from, now, 10000),
        fetchWorkouts(from, now)
      ]);
      
      hrSamples = samplesRes.samples;
      recentWorkouts = workoutsRes.workouts;
      
      setTimeout(renderChart, 50);
    } catch (e) {
      error = (e as Error).message;
    } finally {
      loading = false;
    }
  }

  function renderChart() {
    if (!chartEl) return;
    if (chart) {
      chart.destroy();
      chart = null;
    }
    if (hrSamples.length === 0) return;

    // Ensure data is sorted and strictly increasing for uPlot
    const sorted = [...hrSamples].sort((a, b) => a.ts - b.ts);
    
    const xs: number[] = [];
    const ys: (number | null)[] = [];
    
    let lastTs = -1;
    for (let i = 0; i < sorted.length; i++) {
      const s = sorted[i];
      const tsSec = Math.floor(s.ts / 1000);
      
      if (tsSec <= lastTs) continue; // Skip duplicate timestamps
      
      // If gap is greater than 10 minutes, insert a null to gracefully break the line
      // instead of drawing massive ugly diagonal lines across gaps
      if (lastTs !== -1 && (tsSec - lastTs) > 600) {
        xs.push(lastTs + 1);
        ys.push(null);
      }
      
      xs.push(tsSec);
      ys.push(s.value);
      lastTs = tsSec;
    }

    const data: uPlot.AlignedData = [xs, ys];
    
    // Futuristic styling config
    const lineColor = '#ff007f'; // Neon pink for synthwave
    const axisColor = 'rgba(255, 255, 255, 0.4)';
    const gridColor = 'rgba(255, 255, 255, 0.05)';

    const opts: uPlot.Options = {
      width: chartEl.clientWidth,
      height: 300,
      scales: { x: { time: true } },
      series: [
        {},
        {
          label: 'HR (bpm)',
          stroke: lineColor,
          fill: (u) => {
            const ctx = u.ctx;
            const grad = ctx.createLinearGradient(0, 0, 0, 300);
            grad.addColorStop(0, 'rgba(255, 0, 127, 0.4)');
            grad.addColorStop(1, 'rgba(255, 0, 127, 0.0)');
            return grad;
          },
          width: 2,
          paths: (uPlot.paths as any).spline ? (uPlot.paths as any).spline() : undefined,
          points: { show: false },
        },
      ],
      axes: [
        { 
          stroke: axisColor,
          grid: { stroke: gridColor, width: 1 } 
        },
        { 
          stroke: axisColor, 
          label: 'bpm',
          grid: { stroke: gridColor, width: 1 }
        },
      ],
    };
    chart = new uPlot(opts, data, chartEl);
  }

  function onResize() {
    if (chart && chartEl) chart.setSize({ width: chartEl.clientWidth, height: 300 });
  }

  function setRange(range: '24h' | '7d' | '30d') {
    timeRange = range;
    load();
  }

  onMount(() => {
    load();
    window.addEventListener('resize', onResize);
  });

  onDestroy(() => {
    if (chart) chart.destroy();
    if (typeof window !== 'undefined') window.removeEventListener('resize', onResize);
  });
</script>

<div class="space-y-8 animate-fade-in pb-16">
  <!-- Header & Controls -->
  <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
    <div>
      <h1 class="text-3xl font-extrabold tracking-tight bg-clip-text text-transparent bg-gradient-to-r from-primary to-accent">Command Center</h1>
      <p class="text-sm text-base-content/60">Real-time health telemetry</p>
    </div>
    
    <div class="join shadow-lg shadow-primary/20 bg-base-300 rounded-full p-1 border border-primary/20">
      <button class="join-item btn btn-sm btn-ghost rounded-full {timeRange === '24h' ? 'bg-primary text-primary-content hover:bg-primary' : ''}" on:click={() => setRange('24h')}>24H</button>
      <button class="join-item btn btn-sm btn-ghost rounded-full {timeRange === '7d' ? 'bg-primary text-primary-content hover:bg-primary' : ''}" on:click={() => setRange('7d')}>7D</button>
      <button class="join-item btn btn-sm btn-ghost rounded-full {timeRange === '30d' ? 'bg-primary text-primary-content hover:bg-primary' : ''}" on:click={() => setRange('30d')}>30D</button>
      <button class="join-item btn btn-sm btn-circle btn-ghost" on:click={load} disabled={loading}>
        {#if loading}
          <span class="loading loading-spinner loading-xs text-primary"></span>
        {:else}
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg>
        {/if}
      </button>
    </div>
  </div>

  {#if error}
    <div class="alert alert-error shadow-lg shadow-error/20 border border-error/50">
      <svg xmlns="http://www.w3.org/2000/svg" class="stroke-current shrink-0 h-6 w-6" fill="none" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
      <span>Telemetry Error: {error}</span>
    </div>
  {/if}

  <!-- Core Metrics Grid -->
  <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
    {#if summary}
      <!-- Heart Rate Stat -->
      <div class="stat bg-base-200/50 backdrop-blur rounded-box border border-secondary/20 shadow-lg shadow-secondary/10 relative overflow-hidden group">
        <div class="absolute inset-0 bg-gradient-to-br from-secondary/10 to-transparent opacity-0 group-hover:opacity-100 transition-opacity"></div>
        <div class="stat-figure text-secondary">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" class="inline-block w-8 h-8 stroke-current"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z"></path></svg>
        </div>
        <div class="stat-title text-base-content/70">Avg Heart Rate</div>
        <div class="stat-value text-secondary text-4xl">{summary.heart_rate.avg_hr?.toFixed(0) ?? '—'}</div>
        <div class="stat-desc text-secondary/70 font-medium tracking-wide">
          Min {summary.heart_rate.min_hr ?? '—'} • Max {summary.heart_rate.max_hr ?? '—'}
        </div>
      </div>

      <!-- Steps Stat -->
      <div class="stat bg-base-200/50 backdrop-blur rounded-box border border-accent/20 shadow-lg shadow-accent/10 relative overflow-hidden group">
        <div class="absolute inset-0 bg-gradient-to-br from-accent/10 to-transparent opacity-0 group-hover:opacity-100 transition-opacity"></div>
        <div class="stat-figure text-accent">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" class="inline-block w-8 h-8 stroke-current"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"></path></svg>
        </div>
        <div class="stat-title text-base-content/70">Steps</div>
        <div class="stat-value text-accent text-4xl">{summary.steps.toLocaleString()}</div>
        <div class="stat-desc text-accent/70 font-medium">Daily Goal: 10,000</div>
      </div>

      <!-- Calories Stat -->
      <div class="stat bg-base-200/50 backdrop-blur rounded-box border border-warning/20 shadow-lg shadow-warning/10 relative overflow-hidden group">
        <div class="absolute inset-0 bg-gradient-to-br from-warning/10 to-transparent opacity-0 group-hover:opacity-100 transition-opacity"></div>
        <div class="stat-figure text-warning">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" class="inline-block w-8 h-8 stroke-current"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17.657 18.657A8 8 0 016.343 7.343S7 9 9 10c0-2 .5-5 2.986-7C14 5 16.09 5.777 17.656 7.343A7.975 7.975 0 0120 13a7.975 7.975 0 01-2.343 5.657z"></path></svg>
        </div>
        <div class="stat-title text-base-content/70">Calories</div>
        <div class="stat-value text-warning text-4xl">{summary.calories.toLocaleString()}</div>
        <div class="stat-desc text-warning/70 font-medium">kcal burned</div>
      </div>

      <!-- Distance Stat -->
      <div class="stat bg-base-200/50 backdrop-blur rounded-box border border-info/20 shadow-lg shadow-info/10 relative overflow-hidden group">
        <div class="absolute inset-0 bg-gradient-to-br from-info/10 to-transparent opacity-0 group-hover:opacity-100 transition-opacity"></div>
        <div class="stat-figure text-info">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" class="inline-block w-8 h-8 stroke-current"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path></svg>
        </div>
        <div class="stat-title text-base-content/70">Distance</div>
        <div class="stat-value text-info text-4xl">{(summary.distance / 1000).toFixed(2)}</div>
        <div class="stat-desc text-info/70 font-medium">km traveled</div>
      </div>

      <!-- Floors Stat -->
      <div class="stat bg-base-200/50 backdrop-blur rounded-box border border-primary/20 shadow-lg shadow-primary/10 relative overflow-hidden group md:col-span-2">
        <div class="absolute inset-0 bg-gradient-to-br from-primary/10 to-transparent opacity-0 group-hover:opacity-100 transition-opacity"></div>
        <div class="stat-title text-base-content/70">Floors Climbed</div>
        <div class="stat-value text-primary text-4xl">{summary.floors} <span class="text-lg opacity-50 font-normal">floors</span></div>
        <div class="stat-desc text-primary/70 font-medium">Total ascent: {summary.elevation.toFixed(1)}m</div>
      </div>
      
      <!-- Workouts Summary Stat -->
      <div class="stat bg-base-200/50 backdrop-blur rounded-box border border-success/20 shadow-lg shadow-success/10 relative overflow-hidden group md:col-span-2">
        <div class="absolute inset-0 bg-gradient-to-br from-success/10 to-transparent opacity-0 group-hover:opacity-100 transition-opacity"></div>
        <div class="stat-title text-base-content/70">Workout Sessions</div>
        <div class="stat-value text-success text-4xl">{summary.workouts} <span class="text-lg opacity-50 font-normal">recorded</span></div>
        <div class="stat-desc text-success/70 font-medium">In the selected {timeRange}</div>
      </div>
    {:else}
      <!-- Skeleton Loaders -->
      {#each Array(6) as _}
        <div class="skeleton h-32 w-full rounded-box bg-base-300/50"></div>
      {/each}
    {/if}
  </div>

  <!-- Main Chart Section -->
  <div class="card bg-base-200/60 backdrop-blur-md border border-primary/20 shadow-xl shadow-primary/5">
    <div class="card-body p-4 sm:p-6">
      <div class="flex justify-between items-center mb-2">
        <h2 class="card-title text-xl text-primary font-bold tracking-wider uppercase">Heart Rate Telemetry</h2>
        <div class="badge badge-outline border-primary/50 text-primary">{hrSamples.length} nodes</div>
      </div>
      
      {#if hrSamples.length === 0 && !loading}
        <div class="h-[300px] flex items-center justify-center border-2 border-dashed border-base-content/10 rounded-xl">
          <p class="text-base-content/40 font-mono">NO SIGNAL IN THIS TIMEFRAME</p>
        </div>
      {:else}
        <div bind:this={chartEl} class="w-full relative z-10"></div>
      {/if}
    </div>
  </div>

  <!-- Recent Workouts List -->
  <div class="card bg-base-200/60 backdrop-blur-md border border-accent/20 shadow-xl shadow-accent/5">
    <div class="card-body p-4 sm:p-6">
      <h2 class="card-title text-xl text-accent font-bold tracking-wider uppercase mb-4">Activity Log</h2>
      
      {#if recentWorkouts.length === 0}
        <p class="text-base-content/50 italic text-center py-4">No exercises detected in the {timeRange} window.</p>
      {:else}
        <div class="overflow-x-auto">
          <table class="table table-zebra table-sm">
            <thead>
              <tr class="text-base-content/60 border-b border-accent/20">
                <th>Type</th>
                <th>Date</th>
                <th>Duration</th>
                <th>Distance</th>
                <th>Calories</th>
                <th>Avg HR</th>
              </tr>
            </thead>
            <tbody>
              {#each recentWorkouts as w}
                <tr class="border-b border-base-300/50 hover:bg-base-300/80 transition-colors">
                  <td class="font-semibold text-accent capitalize">{w.kind.toLowerCase().replace('_', ' ')}</td>
                  <td>{new Date(w.start_ts).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</td>
                  <td>
                    {#if w.end_ts}
                      {Math.round((w.end_ts - w.start_ts) / 60000)} min
                    {:else}
                      <span class="badge badge-accent badge-sm badge-outline animate-pulse">Live</span>
                    {/if}
                  </td>
                  <td>{w.distance_m ? `${(w.distance_m / 1000).toFixed(2)} km` : '-'}</td>
                  <td>{w.kcal ? `${Math.round(w.kcal)} kcal` : '-'}</td>
                  <td>{w.avg_hr ? `${Math.round(w.avg_hr)} bpm` : '-'}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>
  </div>
</div>
