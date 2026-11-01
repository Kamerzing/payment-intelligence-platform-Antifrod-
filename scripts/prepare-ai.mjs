// Reads only public local model metadata. Does not pull a model or enable generation.
import assert from 'node:assert/strict';
import {writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
const base='http://127.0.0.1:11435', model='qwen3:4b-instruct';
async function api(path,body){const response=await fetch(base+path,{method:body?'POST':'GET',headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(5000)});assert.equal(response.status,200);return response.json();}
const runtime=await api('/api/version');const tags=await api('/api/tags');const tag=tags.models.find(item=>item.name===model);assert.ok(tag,'Pull qwen3:4b-instruct first.');
const details=await api('/api/show',{model});assert.equal(details.details.quantization_level,'Q4_K_M');assert.equal(details.details.family,'qwen3');assert.match(details.license,/Apache License/);assert.match(tag.digest,/^[a-f0-9]{64}$/);
const metadata={model,digest:tag.digest,quantization:'Q4_K_M',license:'Apache-2.0',license_sha256:createHash('sha256').update(details.license).digest('hex'),runtime_version:runtime.version,options:{temperature:0,seed:7,num_ctx:4096,num_predict:512,num_thread:2,num_batch:128},keep_alive:0,cloud_disabled_required:true,source:'https://ollama.com/library/qwen3:4b-instruct'};
const output=resolve(process.argv[2]??'.env.ai');
try{await writeFile(output,`# Local model identity; generation remains disabled by default.\nPIP_AI_MODEL_DIGEST=${tag.digest}\nPIP_AI_RUNTIME_VERSION=${runtime.version}\n`,{flag:'wx'});console.log('Created model identity file; generation remains disabled.');}catch(error){if(error.code!=='EEXIST')throw error;console.log('Existing model identity file preserved.');}
console.log(JSON.stringify(metadata,null,2));
