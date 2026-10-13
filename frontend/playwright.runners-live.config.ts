import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./runner-live-tests',workers:1,use:{baseURL:'https://localhost:8796',ignoreHTTPSErrors:true,viewport:{width:1440,height:1000}},reporter:'list',timeout:600000});
