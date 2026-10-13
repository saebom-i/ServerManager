import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./performance-tests',workers:1,use:{baseURL:'http://127.0.0.1:8792',viewport:{width:1440,height:1000}},webServer:{command:'npx vite preview --host 127.0.0.1 --port 8792 --strictPort',url:'http://127.0.0.1:8792',reuseExistingServer:false},reporter:'list',timeout:60000});
