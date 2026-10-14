import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./filter-live-tests',workers:1,use:{baseURL:'http://localhost:8797',viewport:{width:1440,height:1000}},reporter:'list',timeout:90000});
