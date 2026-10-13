import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./update-tests',workers:1,use:{baseURL:'http://localhost:8794'},reporter:'list',timeout:180000});
