<script lang="ts">
  import '../app.css';
  import { page } from '$app/stores';
</script>

<div class="min-h-screen flex flex-col bg-gradient-to-br from-base-300 to-base-100 text-base-content safe-top safe-bottom">
  <header class="navbar bg-base-300/50 backdrop-blur-md border-b border-secondary/20 px-4 sticky top-0 z-50">
    <div class="flex-1">
      <a href="/" class="text-xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-primary to-secondary">
        <span class="mr-2">⚡</span>Pulse
      </a>
    </div>
    <div class="flex-none">
      <ul class="menu menu-horizontal px-1 gap-2">
        <li><a href="/" class="hover:text-primary transition-colors" class:text-primary={$page.url.pathname === '/'} class:font-bold={$page.url.pathname === '/'}>Overview</a></li>
        <li><a href="/hr" class="hover:text-secondary transition-colors" class:text-secondary={$page.url.pathname === '/hr'} class:font-bold={$page.url.pathname === '/hr'}>Metrics</a></li>
      </ul>
    </div>
  </header>

  <main class="flex-1 p-4 max-w-5xl w-full mx-auto animate-fade-in">
    <slot />
  </main>
</div>
