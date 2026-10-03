import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./desktop-tests',workers:1,use:{baseURL:'http://localhost:8790',viewport:{width:1440,height:1100},headless:true,actionTimeout:15000},reporter:'list',timeout:120000});
