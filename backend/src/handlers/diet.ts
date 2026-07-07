import { Env } from "../index";
import { json, authOk } from "../utils";

export async function handleFoodLogs(req: Request, url: URL, env: Env): Promise<Response> {
    if (req.method === "GET") {
        const dateParam = url.searchParams.get("date");
        let query = "SELECT * FROM food_logs ORDER BY logged_at DESC LIMIT 100";
        let params: any[] = [];
        
        if (dateParam) {
            // date bound for the day
            const start = new Date(dateParam).setUTCHours(0,0,0,0);
            const end = start + 86400000;
            query = "SELECT * FROM food_logs WHERE logged_at >= ? AND logged_at < ? ORDER BY logged_at DESC";
            params = [start, end];
        }

        const res = await env.DB.prepare(query).bind(...params).all();
        // Parse JSON micros/meta on the fly for the response so client doesn't have to
        const mapped = res.results.map((row: any) => ({
            ...row,
            micros: row.micros ? JSON.parse(row.micros) : null,
            meta: row.meta ? JSON.parse(row.meta) : null
        }));
        return json({ logs: mapped });
    }

    if (req.method === "POST") {
        const body: any = await req.json();
        if (!body.name || typeof body.calories !== "number") return json({ error: "invalid_body" }, 400);

        const now = body.logged_at ?? Date.now();
        const micros = body.micros ? JSON.stringify(body.micros) : null;
        
        const res = await env.DB.prepare(
            "INSERT INTO food_logs (name, logged_at, calories, protein_g, carbs_g, fat_g, micros, source, meta) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).bind(
            body.name,
            now,
            body.calories,
            body.protein_g ?? 0,
            body.carbs_g ?? 0,
            body.fat_g ?? 0,
            micros,
            body.source ?? 'manual',
            body.meta ? JSON.stringify(body.meta) : null
        ).run();

        return json({ ok: true, id: res.meta.last_row_id });
    }

    return json({ error: "method_not_allowed" }, 405);
}

export async function handleGoals(req: Request, url: URL, env: Env): Promise<Response> {
    if (req.method === "GET") {
        const dateParam = url.searchParams.get("date") ?? new Date().toISOString().split("T")[0];
        const res = await env.DB.prepare("SELECT * FROM daily_goals WHERE target_date = ?").bind(dateParam).first();
        return json({ goals: res ?? null });
    }

    if (req.method === "POST") {
        const body: any = await req.json();
        const targetDate = body.target_date ?? new Date().toISOString().split("T")[0];
        
        await env.DB.prepare(
            `INSERT INTO daily_goals (target_date, target_calories, target_protein_g, target_carbs_g, target_fat_g) 
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(target_date) DO UPDATE SET 
                target_calories=excluded.target_calories,
                target_protein_g=excluded.target_protein_g,
                target_carbs_g=excluded.target_carbs_g,
                target_fat_g=excluded.target_fat_g`
        ).bind(
            targetDate,
            body.target_calories ?? null,
            body.target_protein_g ?? null,
            body.target_carbs_g ?? null,
            body.target_fat_g ?? null
        ).run();

        return json({ ok: true });
    }

    return json({ error: "method_not_allowed" }, 405);
}

export async function handleAIFoodParse(req: Request, env: Env): Promise<Response> {
    if (!authOk(req, env)) return json({ error: "unauthorized" }, 401);
    
    const body: any = await req.json();
    const { text, image_base64 } = body;

    if (!text && !image_base64) return json({ error: "No input provided" }, 400);
    if (!env.GEMINI_API_KEY) return json({ error: "GEMINI_API_KEY is not configured" }, 500);

    const promptText = `Analyze the following food item(s) from the text or image provided.
You are a highly accurate dietician AI. Estimate the nutritional content.
Return the result strictly as a valid JSON object without markdown formatting, matching this structure exactly:
{
  "name": "A short descriptive name of the meal",
  "calories": number,
  "protein_g": number,
  "carbs_g": number,
  "fat_g": number,
  "micros": {
    "vitamin_c_mg": number,
    "iron_mg": number,
    "calcium_mg": number
  },
  "explanation": "A very brief 1-sentence reasoning for the estimate"
}`;

    const contents: any[] = [];
    const parts: any[] = [{ text: promptText }];
    
    if (text) {
        parts.push({ text: "User Input: " + text });
    }
    
    if (image_base64) {
        const b64 = image_base64.replace(/^data:image\/\w+;base64,/, "");
        parts.push({
            inline_data: {
                mime_type: "image/jpeg", 
                data: b64
            }
        });
    }

    contents.push({ parts });

    try {
        const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${env.GEMINI_API_KEY}`;
        
        const gRes = await fetch(geminiUrl, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                contents,
                generationConfig: {
                    temperature: 0.2,
                    response_mime_type: "application/json"
                }
            })
        });

        if (!gRes.ok) {
            const err = await gRes.text();
            throw new Error(`Gemini API Error: ${gRes.status} ${err}`);
        }

        const gData: any = await gRes.json();
        const candidate = gData.candidates?.[0]?.content?.parts?.[0]?.text;
        if (!candidate) throw new Error("Empty response from AI");

        const parsed = JSON.parse(candidate);
        return json({ ok: true, result: parsed });
    } catch (e: any) {
        return json({ error: "AI Parsing failed", details: e.message }, 500);
    }
}
