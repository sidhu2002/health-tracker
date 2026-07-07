<script lang="ts">
  import '../app.css';
  import { page } from '$app/stores';
  import { onMount } from 'svelte';

  let token = '';

  onMount(() => {
    token = localStorage.getItem('WATCH_TOKEN') || '';
  });

  function saveToken() {
    localStorage.setItem('WATCH_TOKEN', token);
    const modal = document.getElementById('token_modal') as HTMLDialogElement;
    modal?.close();
  }
</script>

<div class="min-h-screen flex flex-col bg-gradient-to-br from-base-300 to-base-100 text-base-content safe-top safe-bottom">
  <header class="navbar bg-base-300/50 backdrop-blur-md border-b border-secondary/20 px-4 sticky top-0 z-50">
    <div class="flex-1">
      <a href="/" class="text-xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-primary to-secondary">
        <span class="mr-2">s</span>Pulse
      </a>
    </div>
    <div class="flex-none">
      <ul class="menu menu-horizontal px-1 gap-2 items-center">
        <li><a href="/" class="hover:text-primary transition-colors" class:text-primary={$page.url.pathname === '/'} class:font-bold={$page.url.pathname === '/'}>Overview</a></li>
        <li><a href="/dietitian" class="hover:text-accent transition-colors" class:text-accent={$page.url.pathname === '/dietitian'} class:font-bold={$page.url.pathname === '/dietitian'}>Dietitian</a></li>
        <li>
          <button class="btn btn-ghost btn-sm btn-circle" on:click={() => (document.getElementById('token_modal') as HTMLDialogElement).showModal()} title="Settings">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>
          </button>
        </li>
      </ul>
    </div>
  </header>

  <main class="flex-1 p-4 max-w-5xl w-full mx-auto animate-fade-in">
    <slot />
  </main>
</div>

<!-- Token Settings Modal -->
<dialog id="token_modal" class="modal">
  <div class="modal-box bg-base-200 border border-primary/30">
    <h3 class="font-bold text-lg text-primary">Settings</h3>
    <p class="py-4 text-sm text-base-content/80">Enter your secure WATCH_TOKEN to authorize data modification (e.g. logging meals).</p>
    
    <div class="form-control w-full">
      <label class="label"><span class="label-text">WATCH_TOKEN</span></label>
      <input type="password" placeholder="Enter token..." class="input input-bordered w-full" bind:value={token} />
    </div>

    <div class="modal-action">
      <button class="btn" on:click={() => (document.getElementById('token_modal') as HTMLDialogElement).close()}>Cancel</button>
      <button class="btn btn-primary" on:click={saveToken}>Save</button>
    </div>
  </div>
  <form method="dialog" class="modal-backdrop">
    <button>close</button>
  </form>
</dialog>
