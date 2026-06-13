from fastapi import APIRouter, HTTPException

from app.podmix.schemas import (
    AnalysisJobResponse,
    AnalysisRequest,
    AnalysisResultResponse,
)
from app.podmix.use_cases import analysis_store


router = APIRouter()


@router.post("/podcasts/analyze", response_model=AnalysisJobResponse)
def analyze_podcast(request: AnalysisRequest) -> AnalysisJobResponse:
    return analysis_store.create_stub_job(content_kind="podcast", request=request)


@router.post("/livesets/analyze", response_model=AnalysisJobResponse)
def analyze_liveset(request: AnalysisRequest) -> AnalysisJobResponse:
    return analysis_store.create_stub_job(content_kind="liveset", request=request)


@router.get("/jobs/{job_id}", response_model=AnalysisJobResponse)
def get_job(job_id: str) -> AnalysisJobResponse:
    job = analysis_store.get_job(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="analysis job not found")
    return job


@router.get("/analyses/{analysis_id}", response_model=AnalysisResultResponse)
def get_analysis(analysis_id: str) -> AnalysisResultResponse:
    result = analysis_store.get_result(analysis_id)
    if result is None:
        raise HTTPException(status_code=404, detail="analysis result not found")
    return result

