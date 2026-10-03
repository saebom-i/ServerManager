import {test,expect} from '@playwright/test';

test('local console and file manager work through the browser',async({page,request})=>{
 test.setTimeout(180000);
 const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));
 const instances=await (await request.get('/api/instances')).json();
 const server=instances.find((i:any)=>i.config.name==='로컬 검증 서버');
 expect(server).toBeTruthy();expect(server.state).toBe('STOPPED');
 await page.goto('/');
 await page.getByRole('button',{name:/로컬 검증 서버 NeoForge/}).click();
 await expect(page.getByRole('heading',{name:/로컬 검증 서버/})).toBeVisible();
 await expect(page.getByRole('textbox',{name:'서버 명령'})).toBeDisabled();
 await expect(page.getByRole('textbox',{name:'서버 명령'})).toHaveAttribute('placeholder',/서버가 꺼져 있습니다/);
 await page.getByRole('button',{name:'시작',exact:true}).click();
 await expect(page.locator('.status')).toHaveText('실행 중',{timeout:120000});
 await expect(page.getByTestId('rss-value')).toContainText('MiB',{timeout:10000});
 await expect(page.getByTestId('cpu-value')).toContainText('%',{timeout:10000});
 let sent=0;
 await page.route('**/command',async route=>{sent++;await new Promise(r=>setTimeout(r,600));await route.continue();});
 const cmd=page.getByRole('textbox',{name:'서버 명령'});
 const marker='UI_CONSOLE_'+Date.now();
 await cmd.fill('say '+marker);
 await cmd.press('Enter');await cmd.press('Enter');
 await expect(page.locator('.log.input').filter({hasText:marker})).toHaveCount(1,{timeout:20000});
 await expect(page.locator('.log.output').filter({hasText:marker})).toHaveCount(1);
 expect(sent).toBe(1);
 await page.getByRole('button',{name:'종료',exact:true}).click();
 await expect(page.locator('.status')).toHaveText('정지됨',{timeout:60000});
 await page.getByRole('button',{name:'파일 관리자',exact:true}).click();
 await page.getByRole('button',{name:'config',exact:true}).click();
 page.once('dialog',d=>d.accept('browser-check.txt'));
 await page.getByRole('button',{name:'파일',exact:true}).click();
 await page.getByRole('textbox',{name:'파일 내용'}).fill('browser verified\n한글 편집 확인');
 await page.getByRole('button',{name:'저장',exact:true}).click();
 await expect(page.locator('.notice')).toContainText('파일을 저장했습니다');
 await page.getByRole('button',{name:'닫기',exact:true}).click();
 await page.getByRole('button',{name:'browser-check.txt',exact:true}).click();
 await expect(page.getByRole('textbox',{name:'파일 내용'})).toHaveValue('browser verified\n한글 편집 확인');
 const row=page.locator('.file-row').filter({hasText:'browser-check.txt'});
 const downloadPromise=page.waitForEvent('download');await row.getByTitle('다운로드').click();
 const download=await downloadPromise;expect(download.suggestedFilename()).toBe('browser-check.txt');
 await page.locator('input[type=file]').setInputFiles({name:'uploaded.txt',mimeType:'text/plain',buffer:Buffer.from('upload verified')});
 await expect(page.getByRole('button',{name:'uploaded.txt',exact:true})).toBeVisible();
 page.once('dialog',d=>d.accept('config/renamed-browser.txt'));
 await row.getByTitle('이동 또는 이름 변경').click();
 await expect(page.getByRole('button',{name:'renamed-browser.txt',exact:true})).toBeVisible();
 for(const name of ['renamed-browser.txt','uploaded.txt']){
  page.once('dialog',d=>d.accept());
  await page.locator('.file-row').filter({hasText:name}).getByTitle('삭제').click();
  await expect(page.getByRole('button',{name,exact:true})).toHaveCount(0);
 }
 await page.getByRole('button',{name:'서버 설정',exact:true}).click();
 await expect(page.getByText(/물리 메모리/)).toBeVisible();
 const mem=page.getByLabel('최대 메모리 (MiB)');
 expect(Number(await mem.getAttribute('max'))).toBeGreaterThan(512);
 await page.getByRole('button',{name:'콘솔',exact:true}).click();
 await page.screenshot({path:'test-results/local-console.png',fullPage:true});
 expect(errors).toEqual([]);
});

test('slow repeated event responses never duplicate console rows',async({page})=>{
 await page.route('**/events?*',async route=>{
  await new Promise(r=>setTimeout(r,1600));
  await route.fulfill({json:[{seq:900000,time:new Date().toISOString(),kind:'input',text:'unique-delayed-command'}]});
 });
 await page.goto('/');
 await expect(page.locator('.log.input').filter({hasText:'unique-delayed-command'})).toHaveCount(1,{timeout:10000});
 await page.waitForTimeout(4000);
 await expect(page.locator('.log.input').filter({hasText:'unique-delayed-command'})).toHaveCount(1);
});
