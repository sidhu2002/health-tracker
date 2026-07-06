fetch('https://health-api.siddeshwar.com/v1/ai/parse-food', {
    method: 'POST',
    body: JSON.stringify({text: 'a bowl of oatmeal with a banana'}),
    headers: {'Content-Type': 'application/json'}
}).then(r=>r.json()).then(console.log).catch(console.error);