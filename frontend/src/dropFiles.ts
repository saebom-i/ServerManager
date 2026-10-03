export type UploadItem={file:File;relative:string};
export async function droppedFiles(data:DataTransfer):Promise<UploadItem[]>{
 const items=Array.from(data.items);
 const entries=items.map(item=>item.webkitGetAsEntry?.()).filter((entry):entry is FileSystemEntry=>!!entry);
 if(!entries.length)return Array.from(data.files).map(file=>({file,relative:file.name}));
 const result:UploadItem[]=[];
 async function walk(entry:FileSystemEntry,prefix=''):Promise<void>{
  const relative=prefix+entry.name;
  if(entry.isFile){const file=await new Promise<File>((resolve,reject)=>(entry as FileSystemFileEntry).file(resolve,reject));result.push({file,relative});}
  else if(entry.isDirectory){const reader=(entry as FileSystemDirectoryEntry).createReader();while(true){const batch=await new Promise<FileSystemEntry[]>((resolve,reject)=>reader.readEntries(resolve,reject));if(!batch.length)break;for(const child of batch)await walk(child,relative+'/');}}
 }
 for(const entry of entries)await walk(entry);
 return result;
}
