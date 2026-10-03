import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./security-tests',workers:1,use:{baseURL:'http://localhost:8789',viewport:{width:1440,height:1050},headless:true,actionTimeout:15000},reporter:'list',timeout:420000});
