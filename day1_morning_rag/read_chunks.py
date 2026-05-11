import chromadb

# Connettiti alla cartella
client = chromadb.PersistentClient(path="./chroma_db")

# Accedi alla collection
collection = client.get_collection(name="hcl_day1_rag")

# Prendi tutto (ID, documenti e metadati)
data = collection.get()

print(f"Numero di chunk salvati: {len(data['ids'])}")
for i in range(len(data['ids'])): 
    print(f"\nID: {data['ids'][i]}")
    print(f"Metadati: {data['metadatas'][i]}")
    print(f"Testo: {data['documents'][i]}...") 