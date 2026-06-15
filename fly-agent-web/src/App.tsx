import { ChatLayout } from './components/layout/ChatLayout'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { SwePipelinePage } from '@/components/swe/SwePipelinePage'
import { AleStage1Page } from '@/components/ale/AleStage1Page'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<ChatLayout />} />
        <Route path="/swe" element={<SwePipelinePage />} />
        <Route path="/ale" element={<AleStage1Page />} />
      </Routes>
    </BrowserRouter>
  )
}

export default App
