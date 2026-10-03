import {test,expect} from '@playwright/test';

test('version selection recovers from a failed fetch',async({page})=>{
 let calls=0;
 await page.route('**/version-catalog?*',async route=>{
  calls++;
  if(calls===1)await route.fulfill({status:503,json:{message:'일시적인 버전 조회 실패'}});
  else await route.fulfill({json:{versions:['21.1.252','21.1.251'],fetchedAt:new Date().toISOString(),stale:false}});
 });
 await page.goto('/');
 await page.getByRole('button',{name:'서버 만들기',exact:true}).click();
 await expect(page.getByRole('textbox',{name:'NeoForge 버전 직접 입력'})).toBeVisible();
 await expect(page.getByText('일시적인 버전 조회 실패')).toBeVisible();
 await page.getByRole('button',{name:'버전 다시 조회'}).click();
 await expect(page.getByText('공식 NeoForge 버전 목록')).toBeVisible();
 await page.getByRole('button',{name:'목록에서 선택'}).click();
 await expect(page.getByRole('combobox',{name:'NeoForge 버전 선택'})).toHaveValue('21.1.252');
});

test('files can be dropped and dragged into folders',async({page,request})=>{
 const rows=await (await request.get('/api/instances')).json();
 const server=rows.find((i:any)=>i.config.name==='로컬 검증 서버');
 const base='/api/instances/'+server.config.id;
 await request.post(base+'/folders',{data:{path:'drop-target'}});
 await page.goto('/');
 await page.getByRole('button',{name:/로컬 검증 서버 NeoForge/}).click();
 await page.getByRole('button',{name:'파일 관리자',exact:true}).click();
 const data=await page.evaluateHandle(()=>{const data=new DataTransfer();data.items.add(new File(['drag upload verified'],'drag-test.txt',{type:'text/plain'}));return data;});
 await page.locator('.files-panel').dispatchEvent('drop',{dataTransfer:data});
 const row=page.locator('.file-row').filter({hasText:'drag-test.txt'});
 await expect(row).toBeVisible();
 await row.dragTo(page.locator('.file-row').filter({hasText:'drop-target'}));
 await expect(row).toHaveCount(0);
 await page.getByRole('button',{name:'drop-target',exact:true}).click();
 await expect(page.getByRole('button',{name:'drag-test.txt',exact:true})).toBeVisible();
 const result=await request.get(base+'/text?path=drop-target/drag-test.txt');
 expect((await result.json()).content).toBe('drag upload verified');
 await request.delete(base+'/files?path=drop-target');
});

test('mrpack preview imports real Modrinth files and boots NeoForge',async({page,request})=>{
 test.setTimeout(300000);
 test.skip(!process.env.MRPACK_TEST_FILE,'Set MRPACK_TEST_FILE to a supported pack for real installation test');
 const rows=await (await request.get('/api/instances')).json();
 const port=Math.max(25600,...rows.map((r:any)=>r.config.port))+1;
 const name='mrpack 검증 '+Date.now();
 await page.goto('/');
 await page.getByRole('button',{name:'mrpack 가져오기',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'mrpack 가져오기'});
 await dialog.getByLabel('mrpack 파일').setInputFiles(process.env.MRPACK_TEST_FILE!);
 await expect(dialog.getByText('mrpack 로컬 검증 · integration-1')).toBeVisible();
 await dialog.getByLabel('서버 이름',{exact:true}).fill(name);
 await dialog.getByLabel('게임 포트').fill(String(port));
 await dialog.getByRole('button',{name:'가져오기 및 설치'}).click();
 await expect(dialog).toHaveCount(0);
 await expect(page.getByRole('heading',{name:new RegExp(name)})).toBeVisible();
 await expect(page.locator('.status')).toHaveText('정지됨',{timeout:180000});
 const all=await (await request.get('/api/instances')).json();
 const instance=all.find((i:any)=>i.config.name===name);const base='/api/instances/'+instance.config.id;
 expect((await (await request.get(base+'/text?path=config/import-check.txt')).json()).content).toBe('server override verified');
 const files=await (await request.get(base+'/files?path=config')).json();
 expect(files.some((f:any)=>f.name==='client-only.txt')).toBe(false);
 const mods=await (await request.get(base+'/files?path=mods')).json();
 expect(mods.some((f:any)=>f.name.startsWith('ferritecore-'))).toBe(true);
 try{
  await page.getByRole('button',{name:'시작',exact:true}).click();
  await expect(page.locator('.status')).toHaveText('실행 중',{timeout:90000});
  await expect(page.getByTestId('rss-value')).toContainText('MiB');
  await expect(page.getByTestId('cpu-value')).toContainText('%');
  await page.screenshot({path:'test-results/mrpack-resources.png',fullPage:true});
 }finally{
  await request.post(base+'/stop');
  await expect.poll(async()=>{const rows=await (await request.get('/api/instances')).json();return rows.find((i:any)=>i.config.id===instance.config.id).state;},{timeout:60000}).toBe('STOPPED');
 }
});
