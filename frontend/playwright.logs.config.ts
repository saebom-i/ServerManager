import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./log-tests',workers:1,use:{baseURL:'http://localhost:8793',viewport:{width:1440,height:1000}},reporter:'list',timeout:60000});
